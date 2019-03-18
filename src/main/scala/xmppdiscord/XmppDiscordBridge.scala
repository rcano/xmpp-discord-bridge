package xmppdiscord

import language.reflectiveCalls

import net.dv8tion.jda.core.entities.{TextChannel, Message => DMessage, User}
import net.dv8tion.jda.core.events.ReadyEvent
import net.dv8tion.jda.core.events.message.{GenericMessageEvent, MessageReceivedEvent, MessageUpdateEvent}
import net.dv8tion.jda.core.hooks.EventListener
import net.dv8tion.jda.core.{JDABuilder, AccountType}
import rocks.xmpp.addr.Jid
import rocks.xmpp.core.session._
import rocks.xmpp.core.stanza.model.Message
import rocks.xmpp.core.stanza.model.Presence
import rocks.xmpp.extensions.vcard.temp.VCardManager
import rocks.xmpp.im.roster.RosterManager
import rocks.xmpp.im.roster.model.Contact
import rocks.xmpp.im.subscription.PresenceManager
import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.concurrent.SyncVar
import scala.util.Try
import scala.util.control.Exception._

import RegexExtractor._

object XmppDiscordBridge { def main(args: Array[String]): Unit = new XmppDiscordBridge(args)}
class XmppDiscordBridge(args: Array[String]) {

  case class Clargs(discordToken: String = null, user: String = null, xmppServer: String = null, domain: String = null, xmppPort: Int = 5222, domainAndResource: Option[String] = None)

  val clargs = new scopt.OptionParser[Clargs]("xmpp-discord") {
    opt[String]('d', "domain-and-resource").action((x, c) => c.copy(domainAndResource = Some(x))).
    text("specify a domain and resource in the format domain/resource.")
    arg[String]("<discordToken>").action((x, c) => c.copy(discordToken = x)).text("discord token")
    arg[String]("<user>").action((x, c) => c.copy(user = x)).text("xmpp user")
    arg[String]("<server>").action((x, c) => c.copy(xmppServer = x)).text("xmpp server, e.g. talk.google.com")
    arg[String]("<domain>").action((x, c) => c.copy(domain = x)).text("xmpp domain, e.g. gmail.com")
    arg[String]("port").action((x, c) => c.copy(xmppPort = x.toInt)).optional.
    validate(x => Try{x.toInt; ()}.toOption.toRight("port must be an integer")).
    text("xmpp port, e.g. 5222")
  }.parse(args, new Clargs()).getOrElse(sys.exit(1))

  val passwd = System.console.readPassword("password: ")

  connect()
  @tailrec private def connect(): Unit = {
    val networkOnStartup = java.net.NetworkInterface.getNetworkInterfaces.asScala.toArray
    
    val readyLatch = new SyncVar[Unit]()
    val awaitReady = new EventListener {
      override def onEvent(evt) = evt match {
        case evt: ReadyEvent => readyLatch.put(()); evt.getJDA.removeEventListener(this)
        case _ =>
      }
    }
    val jdaClient = new JDABuilder(AccountType.BOT).setAudioEnabled(false).setAutoReconnect(true).
    addEventListener(awaitReady).setToken(clargs.discordToken).build()
    
    val connConf = TcpConnectionConfiguration.builder.hostname(clargs.xmppServer).port(clargs.xmppPort).build()
    val xmppClient = XmppClient.create(clargs.domain, connConf)
    val rosterManager = xmppClient.getManager(classOf[RosterManager])
    val vcardManager = xmppClient.getManager(classOf[VCardManager])

    clargs.domainAndResource.map(_.split("/")) match {
      case Some(Array(domain, resource, _*)) => xmppClient.connect(Jid.ofDomainAndResource(domain, resource))
      case _ => xmppClient.connect()
    }
  
    val res = xmppClient.login(clargs.user, new String(passwd))
    if (res != null) println(res.mkString(", "))

    println("Contacts:")

    readyLatch.get
    println("Connected to discord")
  
    println("Making sure a channel per contact exists")

    val hangoutsGuild = jdaClient.getGuilds.get(0)
    val allChannels = hangoutsGuild.getTextChannels.asScala
    val generalChannel = allChannels.find(_.getName == "general").getOrElse(hangoutsGuild.getController.createTextChannel("general").complete().asInstanceOf[TextChannel])

    @volatile var contactsToChannels: Map[Jid, (Contact, TextChannel)] = Map.empty
    def registerOrCreateChannel(contact: Contact): Unit = {
      Option(contact.getName).orElse(scala.util.Try(vcardManager.getVCard(contact.getJid).getResult).map(_.getFormattedName).toOption) match {
        case Some(name) =>
          val contactWithName = new Contact(contact.getJid, name, contact.isPendingOut, contact.isApproved, contact.getSubscription, contact.getGroups)
          val newChannel = (name.split(" ").mkString("_") + "-" + contact.getJid).
            replace('á', 'a').replace('í', 'i').replace('ú', 'u').replace('é', 'e').replace('ó', 'o').
            replaceAll("[^a-zA-Z0-9]", "-").replaceAll("--+", "-").
            dropWhile(c => c == '-' || c == '_').toLowerCase
            
          allChannels.find(_.getName == newChannel) match {
            case Some(c) =>
              println(c + " already created")
              contactsToChannels += (contact.getJid -> (contactWithName -> c))
            case _ =>
              println("Creating channel " + newChannel)
              val c = hangoutsGuild.getController.createTextChannel(newChannel).complete().asInstanceOf[TextChannel]
              println("done")
              contactsToChannels += (contact.getJid -> (contactWithName -> c))
          }

        case _ =>
          Console.err.println(s"ignoring contact $contact because we couldn't produce a name")
      }

    }
    println("All currently created channels " + allChannels.map(_.getName))
    rosterManager.getContacts.asScala.foreach(registerOrCreateChannel)

    println("All contacts channels")
    contactsToChannels foreach println
    rosterManager.addRosterListener(_.getAddedContacts.asScala foreach registerOrCreateChannel)

    def withChannel(jid: Jid)(f: ((Contact, TextChannel)) => Any): Unit = contactsToChannels.get(jid) match {
      case Some(c) => f(c)
      case _ => println(Console.RED + s"Channel not found for user $jid. This should not happen" + Console.RESET)
    }
  
    //configure bidirectional messaging by forwarding messages from discord to xmpp and viceversa
    xmppClient.addInboundMessageListener { evt =>
      try {
        val msg = evt.getMessage
        if (msg.isNormal || msg.getType == Message.Type.CHAT && msg.getBody != null && msg.getBody.nonEmpty)
          withChannel(msg.getFrom.asBareJid)(_._2.sendMessage(msg.getBody).queue())
        else 
          println("Ignoring message " + msg)
      } catch {
        case e: Exception =>
          e.printStackTrace()
          Try(generalChannel.sendMessage(e.toString).queue())
      }
    }

    type MessageEvent = GenericMessageEvent {
      def getAuthor(): User
      def getMessage(): DMessage
    }
    object MessageEvent {
      def unapply(evt: GenericMessageEvent) = evt match {
        case _: MessageReceivedEvent | _: MessageUpdateEvent => Some(evt.asInstanceOf[MessageEvent])
        case _ => None
      }
    }
  
    //handle incoming discord messages
    jdaClient.addEventListener({
        case MessageEvent(evt) if evt.getAuthor != jdaClient.getSelfUser => 
          val msg = evt.getMessage
        
          try {
            if (msg.getChannel.getIdLong == generalChannel.getIdLong) { //handle command
              msg.getContentDisplay match {
                case "/delete all channels" =>
                  println("deleting all channels")
                  (allChannels - generalChannel) foreach (c => 
                      c.delete().queue(_ => println(s"channel $c deleted"), ex => generalChannel.sendMessage(s"Failed to delete channel $c due to $ex").queue()))
                case "/delete all non bound channels" =>
                  println("deleting non bound channels")
                  (allChannels - generalChannel -- contactsToChannels.values.map(_._2)) foreach (c => 
                      c.delete().queue(_ => println(s"channel $c deleted"), ex => generalChannel.sendMessage(s"Failed to delete channel $c due to $ex").queue()))
                case "/delete created channels" =>
                  println("deleting created channels")
                  contactsToChannels.values.map(_._2) foreach (c => 
                      c.delete().queue(_ => println(s"channel $c deleted"), ex => generalChannel.sendMessage(s"Failed to delete channel $c due to $ex").queue()))

                case regex"/find (.+)$userName" =>
                  val patt = ".*?" + userName.toLowerCase + ".*"
                  val foundUsers = contactsToChannels.values.filter { case (c, _) => c.getName.toLowerCase.matches(patt) || c.toString.matches(patt) }
                  if (foundUsers.isEmpty) generalChannel.sendMessage(s"No user found for $userName")
                  else generalChannel.sendMessage(foundUsers.map(u => u._1.getName + ": " + u._2.getAsMention).mkString("\n")).queue()
            
                case _ =>
              }

              //handle p2p messages
            } else contactsToChannels.find(_._2._2.getIdLong == msg.getChannel.getIdLong) foreach {
              case (jid, _) => 
                xmppClient.sendMessage(new Message(jid, Message.Type.CHAT, msg.getContentDisplay))
                msg.getAttachments.asScala.foreach { attachment => 
                  xmppClient.sendMessage(new Message(jid, Message.Type.CHAT, attachment.getFileName + "\n" + attachment.getUrl))
                }
            }
          } catch {
            case e: Exception =>
              e.printStackTrace()
              Try(generalChannel.sendMessage(e.toString))
          }
        case _ =>
      }: EventListener)

    //handle presence events
    val presenceManager = xmppClient.getManager(classOf[PresenceManager])
    def processPresence(presence: Presence): Unit = Try {
      val from = rosterManager.getContact(presence.getFrom)
      if (from == null) return
      presence.getType match {
        case null => withChannel(from.getJid.asBareJid)(_._2.getManager.setTopic(s"${presence.getShow}: ${presence.getStatus}").queue())
        case Presence.Type.SUBSCRIBE | Presence.Type.UNSUBSCRIBE =>
          generalChannel.sendMessage(s"$from wishes to ${presence.getType}").queue()
        case Presence.Type.UNAVAILABLE => //not useful event
        case _ => withChannel(from.getJid.asBareJid)(_._2.sendMessage(s"$from: " + presence.getType).queue())
      }
    }.failed foreach { ex =>
      ex.printStackTrace()
      Try(generalChannel.sendMessage(s"Failed to update presence $presence due to $ex").queue())
    }
    xmppClient.addInboundPresenceListener(evt => processPresence(evt.getPresence))
    rosterManager.getContacts.asScala.foreach(c => processPresence(presenceManager.getPresence(c.getJid)))
    
    //just monitor the net from now on, if the network interfaces change, reconnect
    while (java.net.NetworkInterface.getNetworkInterfaces.asScala.toArray sameElements networkOnStartup) {
      Thread.sleep(3000)
    }
    println(Console.RED + "DETECTED NETWORK CONFIGURATION CHANGE, RECONNECTING..." + Console.RESET)
    Try(xmppClient.close())
    Try(jdaClient.shutdownNow())
    println("Waiting 10 seconds for the network to stabilize")
    Thread.sleep(10000) // give it some time before reconnecting
    
    while (catching(classOf[java.net.UnknownHostException]).opt(java.net.InetAddress.getAllByName("discordapp.com")).isEmpty) {
      println(Console.RED + "still no network, waiting..." + Console.RESET)
      Thread.sleep(1000)
    }
    connect()
  }
}
