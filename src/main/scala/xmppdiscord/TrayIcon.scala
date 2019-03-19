package xmppdiscord

import java.awt.{Image, MenuItem, PopupMenu, SystemTray}
import javax.imageio.ImageIO
import tangerine.ChainingOps

class TrayIcon {

  private val connectingImage: Image = ImageIO.read(getClass.getResource("/connecting.png"))
  private val connectedImage: Image = ImageIO.read(getClass.getResource("/connected.png"))
  
  private var icon: java.awt.TrayIcon = null
  def setup(): Unit = {
    icon = new java.awt.TrayIcon(connectingImage, "Xmpp-Discord Bridge")
    icon.setPopupMenu(new PopupMenu().tap(_.add(new MenuItem("quit").tap(_.addActionListener(_ => sys.exit(0))))))
    SystemTray.getSystemTray().add(icon)
  }
  
  def showConnecting(): Unit = icon.setImage(connectingImage)
  def showConnected(): Unit = icon.setImage(connectedImage)
}
