// Copyright 2018 UANGEL
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.thing2x.smqd.net.telnet

import java.net.InetAddress
import java.util.Properties

import com.thing2x.smqd.plugin.Service
import com.thing2x.smqd.{SmqSuccess, Smqd}
import com.typesafe.config.Config
import com.typesafe.scalalogging.StrictLogging
import net.wimpi.telnetd.TelnetD

import scala.jdk.CollectionConverters._
import scala.concurrent.Await
import scala.concurrent.duration._

// 10/13/18 - Created by Kwon, Yeong Eon

/** TelnetD-x is implemented in a singletone pattern.
  * So, TelnetService can not be mutiple instantiated.
  */
object TelnetService {
  private var _smqdInstance: Smqd = _
  def smqdInstance_=(inst: Smqd): Unit = _smqdInstance = inst
  def smqdInstance: Smqd = _smqdInstance

  private var _paths: Seq[String] = Nil
  def paths_=(paths: Seq[String]): Unit = _paths = paths
  def paths: Seq[String] = _paths
}

class TelnetService(name: String, smqd: Smqd, config: Config) extends Service(name, smqd, config) with StrictLogging {

  private var telnetD: TelnetD = _

  override def start(): Unit = {
    val properties: Properties = defaultProperties

    config.getConfig("telnet").entrySet.asScala.foreach { entry =>
      val k = entry.getKey
      val v = entry.getValue.render
      properties.setProperty(k, v)
    }

    properties.asScala.foreach(s => logger.trace(s"telnetd property: ${s._1} = ${s._2}"))

    telnetD = TelnetD.createTelnetD(properties)

    TelnetService.smqdInstance = smqd
    TelnetService.paths = config.getStringList("script.path").asScala.toSeq

    ScShell.setDelegate(new ScShellDelegate() {
      // set smqd instance into shell env
      def beforeShellStart(shell: ScShell): Unit = ()
      def afterShellStop(shell: ScShell): Unit = ()
    })

    // delegate login authentication to UserDelegate of Smqd
    LoginShell.setDelegate(new LoginShell.Delegate() {
      override def login(shell: LoginShell, user: String, password: String): Boolean = {
        Await.result(smqd.userLogin(user, password), 3.seconds) match {
          case SmqSuccess(_) => true
          case _             => false
        }
      }

      override def allow(shell: LoginShell, remoteAddress: InetAddress): Boolean = {
        logger.trace(s"telnet connection from remote address: ${remoteAddress.getHostAddress}")
        true
      }
    })

    telnetD.start()
  }

  override def stop(): Unit = {
    if (telnetD != null)
      telnetD.stop()
    telnetD = null
  }

  private def defaultProperties: Properties = {
    val p = new Properties()

    //////////////////////////////////////////////////
    // Terminals
    //////////////////////////////////////////////////

    // List of terminals available and defined below
    p.setProperty("terminals", "vt100,ansi,windoof,xterm")

    // vt100 implementation and aliases
    p.setProperty("term.vt100.class", "net.wimpi.telnetd.io.terminal.vt100")
    p.setProperty("term.vt100.aliases", "default,vt100-am,vt102,dec-vt100")

    // ansi implementation and aliases
    p.setProperty("term.ansi.class", "net.wimpi.telnetd.io.terminal.ansi")
    p.setProperty("term.ansi.aliases", "xterm-256color,color-xterm,vt320,vt220,linux,screen")

    // windoof implementation and aliases
    p.setProperty("term.windoof.class", "net.wimpi.telnetd.io.terminal.Windoof")
    p.setProperty("term.windoof.aliases", "")

    // xterm implementation and aliases
    p.setProperty("term.xterm.class", "net.wimpi.telnetd.io.terminal.xterm")
    p.setProperty("term.xterm.aliases", "")

    //////////////////////////////////////////////////
    // Shells
    //////////////////////////////////////////////////

    // List of shells available and defined below
    p.setProperty("shells", "login,sc,dummy")

    // shell implementations
    p.setProperty("shell.login.class", "com.thing2x.smqd.net.telnet.LoginShell")
    p.setProperty("shell.sc.class", "com.thing2x.smqd.net.telnet.ScShell")
    p.setProperty("shell.dummy.class", "com.thing2x.smqd.net.telnet.DummyShell")

    //////////////////////////////////////////////////
    // Listeners
    //////////////////////////////////////////////////

    p.setProperty("listeners", "default_listener") // comma seperated listener names

    // std listener specific properties
    //Basic listener and connection management settings
    p.setProperty("default_listener.port", "6621")
    p.setProperty("default_listener.floodprotection", "5")
    p.setProperty("default_listener.maxcon", "25")

    // Timeout Settings for connections (ms)
    p.setProperty("default_listener.time_to_warning", "3600000")
    p.setProperty("default_listener.time_to_timedout", "60000")

    // Housekeeping thread active every 1 secs
    p.setProperty("default_listener.housekeepinginterval", "1000")
    p.setProperty("default_listener.inputmode", "character")

    // Login shell
    p.setProperty("default_listener.loginshell", "login")

    // Connection filter class
    p.setProperty("default_listener.connectionfilter", "none")
    p
  }
}
