package xmppdiscord

import java.util.function.Consumer
import rocks.xmpp.addr.Jid
import rocks.xmpp.core.session._
import rocks.xmpp.core.stanza.model.Message
import rocks.xmpp.core.stanza.model.Presence
import rocks.xmpp.extensions.vcard.temp.VCardManager
import rocks.xmpp.im.roster.RosterManager
import rocks.xmpp.im.roster.model.Contact
import rocks.xmpp.im.subscription.PresenceManager
import scala.collection.JavaConverters._
import scala.concurrent.SyncVar
import scala.util.Try
import sx.blah.discord.api.{ClientBuilder => DiscordClientBuilder}
import sx.blah.discord.api.events.{Event, IListener}
import sx.blah.discord.handle.impl.events.{MessageReceivedEvent, MessageUpdateEvent, ReadyEvent}
import sx.blah.discord.handle.obj.IChannel

import RegexExtractor._
import sx.blah.discord.handle.obj.IMessage

object XmppDiscordBridge extends App {

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

  def consumer[T](f: T => Any) = new Consumer[T] { def accept(t) = f(t) }
  def listener[T <: Event](f: T => Any) = new IListener[T] { def handle(t) = f(t) }

  val discordClient = new DiscordClientBuilder().withToken(clargs.discordToken).login()
  val readyLatch = new SyncVar[Unit]()
  discordClient.getDispatcher.registerTemporaryListener(listener[ReadyEvent](e => readyLatch.put(())))

  val connConf = TcpConnectionConfiguration.builder.hostname(clargs.xmppServer).port(clargs.xmppPort).build()
  val xmppClient = XmppClient.create(clargs.domain, connConf)
  val rosterManager = xmppClient.getManager(classOf[RosterManager])
  val vcardManager = xmppClient.getManager(classOf[VCardManager])

  clargs.domainAndResource.map(_.split("/")) match {
    case Some(Array(domain, resource, _*)) => xmppClient.connect(Jid.ofDomainAndResource(domain, resource))
    case _ => xmppClient.connect()
  }
  
  val res = xmppClient.login(clargs.user, new String(passwd))
  println(res.toSeq)

  println("Contacts:")
  val roster = rosterManager.requestRoster().getResult

  readyLatch.get
  println("Connected to discord")
  
  println("Making sure a channel per contact exists")

  val hangoutsGuild = discordClient.getGuilds.get(0)
  val allChannels = hangoutsGuild.getChannels.asScala
  val generalChannel = allChannels.find(_.getName == "general").getOrElse(hangoutsGuild.createChannel("general"))

  @volatile var contactsToChannels: Map[Jid, (Contact, IChannel)] = Map.empty
  def registerOrCreateChannel(contact: Contact): Unit = {
    Option(contact.getName).orElse(scala.util.Try(vcardManager.getVCard(contact.getJid).getResult).map(_.getFormattedName).toOption) match {
      case Some(name) =>
        val contactWithName = new Contact(contact.getJid, name, contact.isPending, contact.isApproved, contact.getSubscription, contact.getGroups)
        val newChannel = (name.split(" ").map(_.capitalize).mkString + "-" + contact.getJid).replace('á', 'a').replace('í', 'i').replace('ú', 'u').
        replace('é', 'e').replace('ó', 'o').replaceAll("[^a-zA-Z0-9]", "-").dropWhile(c => c == '-' || c == '_').toLowerCase
        allChannels.find(_.getName == newChannel) match {
          case Some(c) =>
            println(c + " already created")
            contactsToChannels += (contact.getJid -> (contactWithName -> c))
          case _ =>
            println("Creating channel " + newChannel)
            val c = hangoutsGuild.createChannel(newChannel)
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
  rosterManager.addRosterListener(consumer(_.getAddedContacts.asScala foreach registerOrCreateChannel))

  def withChannel(jid: Jid)(f: ((Contact, IChannel)) => Any): Unit = contactsToChannels.get(jid) match {
    case Some(c) => f(c)
    case _ => println(Console.RED + s"Channel not found for user $jid. This should not happen" + Console.RESET)
  }
  
  //configure bidirectional messaging by forwarding messages from discord to xmpp and viceversa
  xmppClient.addInboundMessageListener(consumer { evt =>
      try {
        val msg = evt.getMessage
        if (msg.isNormal || msg.getType == Message.Type.CHAT && msg.getBody != null && msg.getBody.nonEmpty)
          withChannel(msg.getFrom.asBareJid)(_._2.sendMessage(msg.getBody))
        else 
          println("Ignoring message " + msg)
      } catch {
        case e: Exception =>
          e.printStackTrace()
          Try(generalChannel.sendMessage(e.toString))
      }
    })

  //handle incoming discord messages
  def handleDiscordMessage(msg: IMessage) {
    try {
      if (msg.getChannel.getID == generalChannel.getID) { //handle command
        msg.getContent match {
          case "/delete all channels" =>
            allChannels foreach (c => Try(c.delete()).failed.foreach(ex => Try(generalChannel.sendMessage(s"Failed to delete channel $c due to $ex"))))
          case "/delete created channels" =>
            println("deleting created channels")
            contactsToChannels.values foreach (c => Try(c._2.delete()).failed.foreach(ex => Try(generalChannel.sendMessage(s"Failed to delete channel $c due to $ex"))))

          case regex"/find (.+)$userName" =>
            val patt = ".*?" + userName.toLowerCase + ".*"
            val foundUsers = contactsToChannels.values.filter { case (c, _) => c.getName.toLowerCase.matches(patt) || c.toString.matches(patt) }
            if (foundUsers.isEmpty) generalChannel.sendMessage(s"No user found for $userName")
            else generalChannel.sendMessage(foundUsers.map(u => u._1.getName + ": " + u._2.mention).mkString("\n"))
        }

        //handle p2p messages
      } else contactsToChannels.find(_._2._2.getID == msg.getChannel.getID) foreach {
        case (jid, _) => xmppClient.sendMessage(new Message(jid, Message.Type.CHAT, msg.getContent))
      }
    } catch {
      case e: Exception =>
        e.printStackTrace()
        Try(generalChannel.sendMessage(e.toString))
    }
  }
  discordClient.getDispatcher.registerListener(listener[MessageReceivedEvent](evt => handleDiscordMessage(evt.getMessage)))
  discordClient.getDispatcher.registerListener(listener[MessageUpdateEvent](evt => handleDiscordMessage(evt.getNewMessage)))

  //handle presence events
  val presenceManager = xmppClient.getManager(classOf[PresenceManager])
  def processPresence(presence: Presence): Unit = Try {
    val from = rosterManager.getContact(presence.getFrom)
    println(s"Processing presence from $from: $presence")
    presence.getType match {
      case null => withChannel(from.getJid.asBareJid)(_._2.changeTopic(s"${presence.getShow}: ${presence.getStatus}"))
      case Presence.Type.SUBSCRIBE | Presence.Type.UNSUBSCRIBE =>
        generalChannel.sendMessage(s"$from wishes to ${presence.getType}")
      case Presence.Type.UNAVAILABLE => //not useful event
      case _ => withChannel(from.getJid.asBareJid)(_._2.sendMessage(s"$from: " + presence.getType))
    }
  }.failed foreach (ex => Try(generalChannel.sendMessage(s"Failed to update presence $presence due to $ex")))
  xmppClient.addInboundPresenceListener(consumer(evt => processPresence(evt.getPresence)))
  rosterManager.getContacts.asScala.foreach(c => processPresence(presenceManager.getPresence(c.getJid)))
}
