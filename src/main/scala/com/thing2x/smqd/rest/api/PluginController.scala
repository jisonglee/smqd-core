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

package com.thing2x.smqd.rest.api

import java.io.File

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.{Directives, Route}
import com.thing2x.smqd.util.ConfigUtil._
import com.thing2x.smqd.net.http.HttpServiceContext
import com.thing2x.smqd.plugin.PluginManager._
import com.thing2x.smqd.plugin._
import com.thing2x.smqd.rest.RestController
import com.typesafe.config.{Config, ConfigFactory}
import com.thing2x.smqd.util.FailFastCirceSupport._
import com.typesafe.scalalogging.StrictLogging
import io.circe.Json
import io.circe.syntax._

// 2018. 7. 6. - Created by Kwon, Yeong Eon

class PluginController(name: String, context: HttpServiceContext) extends RestController(name, context) with Directives with StrictLogging {

  override def routes: Route = context.oauth2.authorized { _ => packages ~ plugins }

  private def packages: Route = {
    ignoreTrailingSlash {
      get {
        parameters("curr_page".as[Int].?, "page_size".as[Int].?, "query".as[String].?) { (currPage, pageSize, searchName) =>
          path("packages" / Segment.?) { packageName =>
            getPackages(packageName, searchName, currPage, pageSize)
          }
        }
      } ~
        put {
          path("packages" / Segment / Segment) { (packageName, cmd) =>
            putPackage(packageName, cmd)
          }
        }
    }
  }

  private def plugins: Route = {
    ignoreTrailingSlash {
      get {
        parameters("curr_page".as[Int].?, "page_size".as[Int].?, "query".as[String].?) { (currPage, pageSize, searchName) =>
          path("plugins" / Segment / "config") { pluginName =>
            getPluginConfig(pluginName)
          } ~
            path("plugins" / Segment / "instances" / Segment / "config") { (pluginName, instanceName) =>
              getPluginInstanceConfig(pluginName, instanceName)
            } ~
            path("plugins" / Segment / "instances" / Segment.?) { (pluginName, instanceName) =>
              getPluginInstances(pluginName, instanceName, searchName, currPage, pageSize)
            } ~
            path("plugins" / Segment.?) { pluginName =>
              getPlugins(pluginName, searchName, currPage, pageSize)
            }
        }
      } ~
        put {
          path("plugins" / Segment / "instances" / Segment / Segment) { (pluginName, instanceName, cmd) =>
            putPlugin(pluginName, instanceName, cmd)
          }
        } ~
        path("plugins" / Segment / "instances" / Segment) { (pluginName, instanceName) =>
          post {
            entity(as[com.typesafe.config.Config]) { conf =>
              createPluginInstance(pluginName, instanceName, conf)
            } ~
              entity(as[String]) { str =>
                createPluginInstance(pluginName, instanceName, str)
              }
          } ~
            patch {
              entity(as[com.typesafe.config.Config]) { conf =>
                updatePluginInstance(pluginName, instanceName, conf)
              } ~
                entity(as[String]) { str =>
                  updatePluginInstance(pluginName, instanceName, str)
                }
            } ~
            delete {
              deletePluginInstance(pluginName, instanceName)
            }
        }
    }
  }

  private def getPackages(packageName: Option[String], searchName: Option[String], currPage: Option[Int], pageSize: Option[Int]): Route = {
    val pm = context.smqdInstance.pluginManager
    packageName match {
      case Some(pn) => // exact match
        val rt = pm.packageDefinitions
        rt.find(_.name == pn) match {
          case Some(pkg) =>
            complete(StatusCodes.OK, restSuccess(0, pkg.asJson))
          case None =>
            complete(StatusCodes.NotFound, restError(404, s"Package not found: $pn"))
        }
      case None => // search
        searchName match {
          case Some(search) => // query
            val rt = pm.packageDefinitions
            val result = rt.filter(p => p.name.contains(search)).sorted
            if (result.isEmpty)
              complete(StatusCodes.NotFound, restError(404, s"Package not found, search $search"))
            else
              complete(StatusCodes.OK, restSuccess(0, pagenate(result, currPage, pageSize)))
          case None => // all - retrieve repository definitions instead of package defs.
            val result = pm.repositoryDefinitions.sorted
            complete(StatusCodes.OK, restSuccess(0, pagenate(result, currPage, pageSize)))
        }
    }
  }

  private def execResult(result: ExecResult): Json = {
    result match {
      case ExecSuccess(msg) =>
        restSuccess(0, Json.fromString(msg))
      case ExecFailure(message, Some(cause)) =>
        restError(StatusCodes.InternalServerError.intValue, s"Command failed - $message, ${cause.getMessage}")
      case ExecFailure(message, None) =>
        restError(StatusCodes.InternalServerError.intValue, message)
      case ExecInvalidStatus(message) =>
        restError(StatusCodes.NotImplemented.intValue, message)
      case ExecUnknownCommand(cmd) =>
        restError(StatusCodes.BadRequest.intValue, s"Not implemented command - $cmd")
      case x =>
        restError(StatusCodes.InternalServerError.intValue, s"Unknown response from plugin: $x")
    }
  }

  private def putPackage(packageName: String, cmd: String): Route = {
    val smqdInstance = context.smqdInstance
    val pm = context.smqdInstance.pluginManager
    import smqdInstance.Implicit._
    pm.repositoryDefinition(packageName) match {
      case Some(rdef) =>
        cmd.toLowerCase match {
          case "install" =>
            val jval = for {
              rt <- pm.installPackage(smqdInstance, rdef)
              result = rt match {
                case _: InstallSuccess => restSuccess(0, rdef.asJson)
                case e: InstallResult  => restError(500, e.msg)
              }
            } yield result
            complete(StatusCodes.OK, jval)
          case "reload" =>
            val jval = for {
              rt <- pm.reloadPackage(smqdInstance, rdef)
              result = rt match {
                case _: ReloadSuccess => restSuccess(0, rdef.asJson)
                case e: ReloadResult  => restError(500, e.msg)
              }
            } yield result
            complete(StatusCodes.OK, jval)
          case command =>
            val params: Map[String, Any] = if (pm.libDirectory.isDefined) Map("plugin.dir" -> pm.libDirectory.get) else Map.empty
            val result = rdef.exec(command, params) map {
              case ExecSuccess(_) => restSuccess(0, rdef.asJson)
              case rt             => execResult(rt)
            }
            complete(StatusCodes.OK, result)
        }
      case None =>
        complete(StatusCodes.NotFound, s"Package not found :$packageName")
    }
  }

  private def putPlugin(pluginName: String, instanceName: String, command: String): Route = {
    val smqdInstance = context.smqdInstance
    val pm = context.smqdInstance.pluginManager
    val instanceOpt = pm.instance(pluginName, instanceName)
    import smqdInstance.Implicit._
    instanceOpt match {
      case Some(instance) =>
        val result = instance.exec(command.toLowerCase) map {
          case ExecSuccess(_) => restSuccess(0, instance.asJson)
          case rt             => execResult(rt)
        }
        complete(StatusCodes.OK, result)
      case None =>
        complete(StatusCodes.NotFound, restError(404, s"Plugin instance not found - $pluginName, $instanceName"))
    }
  }

  private def getPlugins(pluginName: Option[String], searchName: Option[String], currPage: Option[Int], pageSize: Option[Int]): Route = {
    val pm = context.smqdInstance.pluginManager
    pluginName match {
      case Some(pname) => // exact match
        pm.pluginDefinition(pname) match {
          case Some(p) =>
            complete(StatusCodes.OK, restSuccess(0, p.asJson))
          case None =>
            complete(StatusCodes.NotFound, s"Plugin not found plugin: $pname")
        }
      case None => // search
        searchName match {
          case Some(search) => // query
            val result = pm.pluginDefinitions(search).sorted
            if (result.isEmpty)
              complete(StatusCodes.NotFound, restError(404, s"Plugin not found, search $search"))
            else
              complete(StatusCodes.OK, restSuccess(0, pagenate(result, currPage, pageSize)))
          case None => // all
            val result = pm.pluginDefinitions.sorted
            complete(StatusCodes.OK, restSuccess(0, pagenate(result, currPage, pageSize)))
        }
    }
  }

  private def getPluginInstances(pluginName: String, instanceName: Option[String], searchName: Option[String], currPage: Option[Int], pageSize: Option[Int]): Route = {
    val pm = context.smqdInstance.pluginManager
    instanceName match {
      case Some(instName) => // exact match
        pm.instance(pluginName, instName) match {
          case Some(inst) =>
            complete(StatusCodes.OK, restSuccess(0, inst.asJson))
          case None =>
            complete(StatusCodes.NotFound, s"Plugin instance not found plugin: $pluginName, instance: $instName")
        }
      case None => // search
        searchName match {
          case Some(search) => // query
            val result = pm.instances(pluginName, search).sorted
            if (result.isEmpty)
              complete(StatusCodes.NotFound, s"Plugin instance not found plugin: $pluginName, search $search")
            else
              complete(StatusCodes.OK, restSuccess(0, pagenate(result, currPage, pageSize)))
          case None => // all
            val result = pm.instances(pluginName).sorted
            complete(StatusCodes.OK, restSuccess(0, pagenate(result, currPage, pageSize)))
        }
    }
  }

  private def getPluginConfig(pluginName: String): Route = {
    val pm = context.smqdInstance.pluginManager
    pm.pluginDefinition(pluginName) match {
      case Some(pdef) =>
        val result = Json.obj(
          ("default-config", pdef.defaultConfig.asJson),
          ("config-schema", pdef.configSchema.asJson)
        )
        complete(StatusCodes.OK, restSuccess(0, result))
      case None =>
        complete(StatusCodes.NotFound, restError(404, s"Plugine not found $pluginName"))
    }
  }

  private def getPluginInstanceConfig(pluginName: String, instanceName: String): Route = {
    val pm = context.smqdInstance.pluginManager
    pm.instance(pluginName, instanceName) match {
      case Some(inst) =>
        val autoStart = inst.autoStart
        inst.instance match {
          case ap: AbstractPlugin =>
            complete(
              StatusCodes.OK,
              restSuccess(
                0,
                Json.obj(
                  ("auto-start", Json.fromBoolean(autoStart)),
                  ("config", ap.config.asJson)
                )
              )
            )
          case _ =>
            complete(
              StatusCodes.OK,
              restSuccess(
                0,
                Json.obj(
                  ("auto-start", Json.fromBoolean(autoStart)),
                  ("config", Json.Null)
                )
              )
            )
          //complete(StatusCodes.NotAcceptable, s"Plugin instance is not a configurable")
        }

      case None =>
        complete(StatusCodes.NotFound, s"Plugin instance not found plugin: $pluginName, instance: $instanceName")
    }
  }

  private def createPluginInstance(pluginName: String, instanceName: String, configString: String): Route = {
    val conf = ConfigFactory.parseString(configString)
    createPluginInstance(pluginName, instanceName, conf)
  }

  private def createPluginInstance(pluginName: String, instanceName: String, conf: Config): Route = {
    val smqdInstance = context.smqdInstance
    smqdInstance.pluginManager.configDirectory match {
      case Some(confDir) =>
        val file = new File(confDir, s"$pluginName-$instanceName.conf")
        if (file.exists()) {
          complete(StatusCodes.PreconditionFailed, restError(412, "same plugin and instance name already exists"))
        } else if (!file.getParentFile.canRead || !file.getParentFile.canWrite) {
          complete(StatusCodes.PreconditionFailed, restError(412, s"access denied. can't write the instance '$pluginName-$instanceName'"))
        } else {
          smqdInstance.pluginManager.createInstanceConfigFile(smqdInstance, pluginName, instanceName, file, conf) match {
            case Some(_) =>
              getPluginInstanceConfig(pluginName, instanceName)
            case None =>
              complete(StatusCodes.InternalServerError, restError(500, s"Unable to load plugin '$pluginName-$instanceName'"))
          }
        }

      case None =>
        complete(StatusCodes.InternalServerError, restError(500, s"plugin configuration directory is not set"))
    }
  }

  private def updatePluginInstance(pluginName: String, instanceName: String, configString: String): Route = {
    val conf = ConfigFactory.parseString(configString)
    updatePluginInstance(pluginName, instanceName, conf)
  }

  private def updatePluginInstance(pluginName: String, instanceName: String, conf: Config): Route = {
    val smqdInstance = context.smqdInstance
    val pm = context.smqdInstance.pluginManager
    pm.configDirectory match {
      case Some(confDir) =>
        val file = new File(confDir, s"$pluginName-$instanceName.conf")

        (file.exists, pm.instance(pluginName, instanceName)) match {
          case (true, Some(instDef)) =>
            instDef.instance.status match {
              case InstanceStatus.RUNNING | InstanceStatus.STARTING | InstanceStatus.STOPPING =>
                complete(StatusCodes.PreconditionFailed, restError(412, s"plugin instance is still running"))
              case InstanceStatus.STOPPED | InstanceStatus.FAIL =>
                if (pm.updateInstanceConfigFile(smqdInstance, pluginName, instanceName, file, conf)) {
                  getPluginInstanceConfig(pluginName, instanceName)
                } else {
                  complete(StatusCodes.InternalServerError, restError(500, s"Fail to delete instance '$pluginName' '$instanceName'"))
                }
            }
          case _ =>
            complete(StatusCodes.NotFound, restError(404, s"Not found instance or configuration '$pluginName' '$instanceName'"))
        }

      case None =>
        complete(StatusCodes.NotFound, restError(404, s"Not found instance configuration '$pluginName' '$instanceName'"))
    }
  }

  private def deletePluginInstance(pluginName: String, instanceName: String): Route = {
    val smqdInstance = context.smqdInstance
    val pm = context.smqdInstance.pluginManager
    pm.configDirectory match {
      case Some(confDir) =>
        val file = new File(confDir, s"$pluginName-$instanceName.conf")

        (file.exists, pm.instance(pluginName, instanceName)) match {
          case (true, Some(instDef)) =>
            instDef.instance.status match {
              case InstanceStatus.RUNNING | InstanceStatus.STARTING | InstanceStatus.STOPPING =>
                complete(StatusCodes.PreconditionFailed, restError(412, s"plugin instance is still running"))
              case InstanceStatus.STOPPED | InstanceStatus.FAIL =>
                if (pm.deleteInstanceConfigFile(smqdInstance, pluginName, instanceName, file))
                  complete(StatusCodes.OK, restSuccess(0, Json.obj(("success", Json.fromString("plugin instance deleted")))))
                else
                  complete(StatusCodes.InternalServerError, restError(500, s"Fail to delete instance '$pluginName' '$instanceName'"))
            }
          case _ =>
            complete(StatusCodes.NotFound, restError(404, s"Not found instance or configuration '$pluginName' '$instanceName'"))
        }
      case None =>
        complete(StatusCodes.NotFound, restError(404, s"Not found instance configuration '$pluginName' '$instanceName'"))
    }
  }
}
