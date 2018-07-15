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

package com.thing2x.smqd.net.http

import java.net.InetSocketAddress

import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.directives.LoggingMagnet
import akka.http.scaladsl.settings.ServerSettings
import akka.http.scaladsl.{ConnectionContext, Http, server}
import akka.stream.scaladsl.Sink
import com.thing2x.smqd._
import com.thing2x.smqd.plugin.Service
import com.thing2x.smqd.rest.RestController
import com.typesafe.config.Config
import com.typesafe.scalalogging.StrictLogging

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success}

// 2018. 6. 19. - Created by Kwon, Yeong Eon

class HttpService(name: String, smqdInstance: Smqd, config: Config) extends Service(name, smqdInstance, config) with StrictLogging with CORSHandler {

  val corsEnabled: Boolean = config.getOptionBoolean("cors.enabled").getOrElse(true)
  val localEnabled: Boolean = config.getOptionBoolean("local.enabled").getOrElse(true)
  val localAddress: String = config.getOptionString("local.address").getOrElse("127.0.0.1")
  val localPort: Int = config.getOptionInt("local.port").getOrElse(80)
  val localBindAddress: String = config.getOptionString("local.bind.address").getOrElse("0.0.0.0")
  val localBindPort: Int = config.getOptionInt("local.bind.port").getOrElse(localPort)

  val localSecureEnabled: Boolean = config.getOptionBoolean("local.secure.enabled").getOrElse(false)
  val localSecureAddress: String = config.getOptionString("local.secure.address").getOrElse("127.0.0.1")
  val localSecurePort: Int = config.getOptionInt("local.secure.port").getOrElse(443)
  val localSecureBindAddress: String = config.getOptionString("local.secure.address").getOrElse("0.0.0.0")
  val localSecureBindPort: Int = config.getOptionInt("local.secure.port").getOrElse(localSecurePort)

  private val oauth2SecretKey: String = config.getOptionString("oauth2.secret_key").getOrElse("default_key")
  val oauth2TokenExpire: Duration = config.getOptionDuration("oauth2.token_expire").getOrElse(30.minutes)
  val oauth2RefreshTokenExpire: Duration = config.getOptionDuration("oauth2.refresh_token_expire").getOrElse(4.hours)
  val oauth2Algorithm: String = config.getOptionString("oauth2.algorithm").getOrElse("HS256")

  val oauth2 = OAuth2(oauth2SecretKey, oauth2Algorithm, oauth2TokenExpire, oauth2RefreshTokenExpire)

  private var binding: Option[ServerBinding] = None
  private var tlsBinding: Option[ServerBinding] = None
  private var finalRoutes: Route = _

  private var localEndpoint: Option[String] = None
  private var secureEndpoint: Option[String] = None
  // Endpoint address of this http server
  def endpoint: EndpointInfo = EndpointInfo(localEndpoint, secureEndpoint)

  override def start(): Unit = {
    logger.info(s"[$name] Starting...")
    logger.debug(s"[$name] local enabled : $localEnabled")
    if (localEnabled) {
      logger.debug(s"[$name] local address : $localAddress:$localPort")
      logger.debug(s"[$name] local bind    : $localBindAddress:$localBindPort")
    }
    logger.debug(s"[$name] secure enabled: $localSecureEnabled")
    if (localSecureEnabled) {
      logger.debug(s"[$name] secure address: $localSecureAddress:$localSecurePort")
      logger.debug(s"[$name] secure bind   : $localSecureBindAddress:$localSecureBindPort")
    }

    import smqd.Implicit._

    val logAdapter: HttpServiceLogger = new HttpServiceLogger(logger, name)

    // load routes configuration
    val routes = if (config.hasPath("routes")) loadRouteFromConfig(config.getConfig("routes")) else Set(emptyRoute)

    // merge all routes into a single route value
    // then encapsulate with log directives
    finalRoutes = logRequestResult(LoggingMagnet(_ => logAdapter.accessLog(System.nanoTime))) {
      val rs = if (routes.isEmpty) {
        emptyRoute
      }
      else if (routes.size == 1)
        routes.head
      else {
        routes.tail.foldLeft(routes.head)((prev, r) => prev ~ r)
      }

      if (corsEnabled) corsHandler(rs) else rs
    }

    val handler = Route.asyncHandler(finalRoutes)

    if (localEnabled) {
      val serverSource = Http().bind(localBindAddress, localBindPort, ConnectionContext.noEncryption(), ServerSettings(system), logAdapter)
      val bindingFuture = serverSource.to(Sink.foreach{ connection =>
        connection.handleWithAsyncHandler(httpRequest => handler(httpRequest))
      }).run()

      bindingFuture.onComplete {
        case Success(b) =>
          binding = Some(b)
          localEndpoint = Some(s"http://$localAddress:${b.localAddress.getPort}")
          localBound(b.localAddress)
          logger.info(s"[$name] Started. listening ${b.localAddress}")
        case Failure(e) =>
          logger.error(s"[$name] Failed", e)
          scala.sys.exit(-1)
      }
    }

    smqdInstance.tlsProvider match {
      case Some(tlsProvider) if localSecureEnabled =>
        tlsProvider.sslContext match {
          case Some(sslContext) =>
            val connectionContext = ConnectionContext.https(sslContext)
            val serverSource = Http().bind(localSecureBindAddress, localSecureBindPort, connectionContext, ServerSettings(system), logAdapter)
            val tlsBindingFuture = serverSource.to(Sink.foreach{ connection =>
              connection.handleWithAsyncHandler(httpRequest => handler(httpRequest))
            }).run()

            tlsBindingFuture.onComplete {
              case Success(b) =>
                tlsBinding = Some(b)
                secureEndpoint = Some(s"https://$localSecureAddress:${b.localAddress.getPort}")
                localSecureBound(b.localAddress)
                logger.info(s"[$name] Started. listening ${b.localAddress}")
              case Failure(e) =>
                logger.error(s"[$name] Failed", e)
                scala.sys.exit(-1)
            }
          case _ =>
        }
      case _ =>
    }
  }

  override def stop(): Unit = {
    logger.info(s"[$name] Stopping...")
    import smqdInstance.Implicit._

    binding match {
      case Some(b) =>
        try {
          b.unbind().onComplete { // trigger unbinding from the port
            _ => logger.debug(s"[$name] unbind ${b.localAddress.toString} done.")
          }
        }
        catch {
          case ex: java.util.NoSuchElementException =>
            logger.warn(s"[$name] Binding was unbound before it was completely finished")
          case ex: Throwable =>
            logger.warn(s"[$name] plain tcp port unbind failed.", ex)
        }
      case None =>
    }

    tlsBinding match {
      case Some(b) =>
      try {
        b.unbind().onComplete { // trigger unbinding from the port
          _ => logger.info(s"[$name] unbind ${b.localAddress.toString} done.")
        }
      }
      catch {
        case ex: java.util.NoSuchElementException =>
          logger.warn(s"[$name] Binding was unbound before it was completely finished")
        case ex: Throwable =>
          logger.warn(s"[$name] tls tcp port unbind failed.", ex)
      }
      case None =>
    }

    logger.info(s"[$name] Stopped.")
  }

  protected def trimSlash(p: String): String = rtrimSlash(ltrimSlash(p))
  protected def ltrimSlash(p: String): String = if (p.startsWith("/")) p.substring(1) else p
  protected def rtrimSlash(p: String): String = if (p.endsWith("/")) p.substring(0, p.length - 1) else p

  def localBound(address: InetSocketAddress): Unit = {
  }

  def localSecureBound(address: InetSocketAddress): Unit = {
  }

  def routes: Route = finalRoutes

  private def loadRouteFromConfig(config: Config): Set[server.Route] = {
    val names = config.entrySet().asScala.map(entry => entry.getKey)
      .filter(key => key.endsWith(".prefix"))
      .map( k => k.substring(0, k.length - ".prefix".length))
      .filter(k => config.hasPath(k+".class"))
    logger.info(s"[$name] routes = "+names.mkString(", "))
    names.map{ rname =>
      val conf = config.getConfig(rname)
      val className = conf.getString("class")
      val prefix = conf.getString("prefix")
      val tokens = prefix.split(Array('/', '"')).filterNot( _ == "") // split prefix into token array
      val clazz = getClass.getClassLoader.loadClass(className)    // load a class that inherits RestController

      val ctrl = try {
        val context = new HttpServiceContext(this, oauth2, smqdInstance, conf)
        val cons = clazz.getConstructor(classOf[String], classOf[HttpServiceContext]) // find construct that has parameters(String, HttpServiceContext)
        cons.newInstance(rname, context).asInstanceOf[RestController]
      } catch {
        case _: NoSuchMethodException =>
          val cons = clazz.getConstructor(classOf[String], classOf[Smqd], classOf[Config]) // find construct that has parameters (String, Smqd, Config)
          logger.warn("!!Warning!! controller has deprecated constructor (String, Smqd, Config), update it with new constructor api (String, HttpServiceContext)")
          cons.newInstance(rname, smqdInstance, conf).asInstanceOf[RestController] // create instance of RestController
      }

      logger.debug(s"[$name] add route $rname: $prefix = $className")

      // make pathPrefix routes from tokens
      tokens.foldRight(ctrl.routes) { (tok, routes) => pathPrefix(tok)(routes)}
    }.toSet
  }

  private val emptyRoute: Route = {
    get {
      complete(StatusCodes.InternalServerError, "It works, but there is no way to go.")
    }
  }
}
