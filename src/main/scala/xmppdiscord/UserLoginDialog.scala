package xmppdiscord

import javafx.application.Platform
import javafx.beans.value.ChangeListener
import javafx.event.ActionEvent
import javafx.geometry.Pos
import javafx.scene.control.{ButtonType, ButtonBar, Dialog, PasswordField, Button, ProgressBar, Label}
import javafx.scene.image.ImageView
import javafx.scene.paint.Color
import javafx.stage.Stage
import rocks.xmpp.addr.Jid
import rocks.xmpp.core.net.client.SocketConnectionConfiguration
import rocks.xmpp.core.session.XmppClient
import scala.concurrent._
import tangerine._, JfxControls._

class UserLoginDialog(user: String, xmppServer: String = null, domain: String = null, xmppPort: Int = 5222, domainAndResource: Option[String] = None) extends Dialog[(String, XmppClient)] {
  private val loginButtonType = new ButtonType("Login", ButtonBar.ButtonData.OK_DONE)
  getDialogPane.getButtonTypes.addAll(loginButtonType, ButtonType.CANCEL)
  JfxUtils.showingProperty(getDialogPane).addListener((_, oldv, newv) => if (newv) getDialogPane.getScene.getWindow.asInstanceOf[Stage].setResizable(true))
  
  setGraphic(new ImageView("/hangouts.png").tap { iv => iv.setSmooth(true); iv.setFitWidth(200); iv.setPreserveRatio(true) })
  setTitle("Login with Hangouts password")
  
  private val tokenTextField = new PasswordField().tap { tf => tf setPromptText "token"; tf.setMaxWidth(Double.MaxValue); tf setPrefColumnCount 30 }
  private val loadingIndicator = new ProgressBar().tap { pb => pb setVisible false; pb setMaxWidth Double.MaxValue }
  private val statusMessage = new Label().tap { l => l setTextFill Color.RED; l setVisible false }
  getDialogPane setContent vbox(tokenTextField, statusMessage, loadingIndicator)(alignment = Pos.BASELINE_LEFT, spacing = 2.em, fillWidth = true)
  
  val loginButton = getDialogPane.lookupButton(loginButtonType).asInstanceOf[Button]
  tokenTextField.textProperty.isEmpty foreach (b => loginButton.setDisable(b))
  loginButton.addEventFilter[ActionEvent](ActionEvent.ACTION, evt => {
      evt.consume()
      if (!tokenTextField.getText.isEmpty) {
        loginButton setDisable true
        loadingIndicator setVisible true
        
        val logingIn = Future {
          val connConf = SocketConnectionConfiguration.builder.hostname(xmppServer).port(xmppPort).build()
          val xmppClient = XmppClient.create(domain, connConf)
          
          domainAndResource.map(_.split("/")) match {
            case Some(Array(domain, resource, _*)) => xmppClient.connect(Jid.ofDomainAndResource(domain, resource))
            case _ => xmppClient.connect()
          }
          val res = xmppClient.login(user, new String(tokenTextField.getText))
          if (res != null) println(res.mkString(", "))
          xmppClient
        }(ExecutionContext.fromExecutor(r => new Thread(r).start()))
        
        logingIn.onComplete { result =>
          loadingIndicator setVisible false
          result.fold(ex => {
              ex.printStackTrace
              statusMessage setText ex.getMessage
              statusMessage setVisible true
              lazy val textChangeListener: ChangeListener[String] = (_, _, newText) => {
                tokenTextField.textProperty.removeListener(textChangeListener)
                statusMessage setVisible false
                loginButton setDisable false
              }
              tokenTextField.textProperty.addListener(textChangeListener)
            }, xmppClient => {
              println("xmpp connected")
              setResult(tokenTextField.getText -> xmppClient)
              close()
            })
        }(ExecutionContext.fromExecutor(Platform.runLater))
      }
    })
  
  setResultConverter(button => null) //this can only be triggered by the cancel button
}