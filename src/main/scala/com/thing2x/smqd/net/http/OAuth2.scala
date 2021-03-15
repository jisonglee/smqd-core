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

import akka.http.scaladsl.model.headers.{Authorization, OAuth2BearerToken}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{AuthorizationFailedRejection, Directive1}
import com.thing2x.smqd.net.http.OAuth2.{OAuth2Claim, OAuth2JwtToken, OAuth2RefreshClaim, clock}
import com.typesafe.scalalogging.StrictLogging
import pdi.jwt.{Jwt, JwtAlgorithm, JwtClaim}

import scala.concurrent.duration.Duration
import scala.util.{Failure, Success}
import io.circe._
import io.circe.syntax._
import io.circe.generic.auto._
import io.circe.parser._

import java.time.Clock

// 2018. 7. 14. - Created by Kwon, Yeong Eon

/**
  */
object OAuth2 {
  implicit val clock: Clock = Clock.systemUTC

  def apply(secretKey: String, algorithmName: String, tokenExpire: Duration, refreshTokenExpire: Duration): OAuth2 = {
    val algorithm = JwtAlgorithm.fromString(algorithmName)
    new OAuth2(secretKey, tokenExpire, refreshTokenExpire, algorithm)
  }

  case class OAuth2JwtToken(tokenType: String, accessToken: String, accessTokenExpire: Long, refreshToken: String, refreshTokenExpire: Long)
//  implicit val OAuth2JwtTokenFormat: RootJsonFormat[OAuth2JwtToken] = jsonFormat5(OAuth2JwtToken)

  case class OAuth2Claim(identifier: String, attributes: Map[String, String] = Map.empty) {
    def contains(key: String): Boolean = attributes.contains(key)
    def getBoolean(key: String): Option[Boolean] = if (attributes.contains(key)) Some(attributes(key).toLowerCase.equals("true")) else None
    def getString(key: String): Option[String] = if (attributes.contains(key)) Some(attributes(key)) else None
    def getInt(key: String): Option[Int] = if (attributes.contains(key)) Some(attributes(key).toInt) else None
    def getLong(key: String): Option[Long] = if (attributes.contains(key)) Some(attributes(key).toLong) else None
    def getFloat(key: String): Option[Float] = if (attributes.contains(key)) Some(attributes(key).toFloat) else None
    def getDouble(key: String): Option[Double] = if (attributes.contains(key)) Some(attributes(key).toDouble) else None

  }

//  implicit val OAuth2ClaimFormat: RootJsonFormat[OAuth2Claim] = jsonFormat2(OAuth2Claim)

  case class OAuth2RefreshClaim(identifier: String, attributes: Map[String, String] = Map.empty) {
    def contains(key: String): Boolean = attributes.contains(key)
    def getBoolean(key: String): Option[Boolean] = if (attributes.contains(key)) Some(attributes(key).toLowerCase.equals("true")) else None
    def getString(key: String): Option[String] = if (attributes.contains(key)) Some(attributes(key)) else None
    def getInt(key: String): Option[Int] = if (attributes.contains(key)) Some(attributes(key).toInt) else None
    def getLong(key: String): Option[Long] = if (attributes.contains(key)) Some(attributes(key).toLong) else None
    def getFloat(key: String): Option[Float] = if (attributes.contains(key)) Some(attributes(key).toFloat) else None
    def getDouble(key: String): Option[Double] = if (attributes.contains(key)) Some(attributes(key).toDouble) else None
  }

//  implicit val OAuth2RefreshClaimFormat: RootJsonFormat[OAuth2RefreshClaim] = jsonFormat2(OAuth2RefreshClaim)

//  private[http] case class OAuth2JwtRefreshToken(username: String)
//  private[http] implicit val OAuth2JwtRefreshTokenFormat: RootJsonFormat[OAuth2JwtRefreshToken] = jsonFormat1(OAuth2JwtRefreshToken)
}

class OAuth2(secretKey: String, tokenExpire: Duration, refreshTokenExpire: Duration, algorithm: JwtAlgorithm) extends StrictLogging {

  private var isSimulation = false
  private var simulationIdentifier = ""

  private[http] def setSimulationMode(isSimulation: Boolean, simulationIdentifier: String): Unit = {
    this.isSimulation = isSimulation
    this.simulationIdentifier = simulationIdentifier
  }

  private def verifyToken(token: String): Option[OAuth2Claim] = {
    Jwt.decode(token, secretKey, JwtAlgorithm.allHmac()) match {
      case Success(claim) =>
        parse(claim.toJson) match {
          case Right(json) =>
            json.as[OAuth2Claim] match {
              case Right(parsedClaim) => Some(parsedClaim)
              case Left(ex) =>
                logger.debug("JWT Json decoding failure", ex)
                None
            }
          case Left(ex) =>
            logger.debug("JWT Json parsing failure", ex)
            None
        }
      case Failure(ex) =>
        logger.debug("JWT token decodeing failure", ex)
        None
    }
  }

  private def bearerToken: Directive1[Option[String]] =
    for {
      authBearerHeader <- optionalHeaderValueByType(classOf[Authorization]).map(extractBearerToken)
      xAuthCookie <- optionalCookie("X-Authorization-Token").map(_.map(_.value))
    } yield authBearerHeader.orElse(xAuthCookie)

  private def extractBearerToken(authHeader: Option[Authorization]): Option[String] =
    authHeader match {
      case Some(Authorization(OAuth2BearerToken(header))) => Option(header)
      case _                                              => None
    }

  def authorized: Directive1[OAuth2Claim] = {
    if (isSimulation) {
      val claim = OAuth2Claim(simulationIdentifier)
      provide(claim)
    } else {
      bearerToken.flatMap {
        case Some(token) =>
          verifyToken(token) match {
            case Some(claim: OAuth2Claim) =>
              provide(claim)
            case None =>
              logger.debug("Jwt authorization rejected: token verification failed")
              reject(AuthorizationFailedRejection)
          }
        case _ =>
          logger.debug("Jwt authorization rejected: bearerToken not found")
          reject(AuthorizationFailedRejection)
      }
    }
  }

  private def issueJwt0(claim: OAuth2Claim, refreshClaim: OAuth2RefreshClaim): OAuth2JwtToken = {
    val tokenJson = claim.asJson.noSpaces
    val refreshJson = refreshClaim.asJson.noSpaces

    val access = Jwt.encode(JwtClaim(tokenJson).issuedNow.expiresIn(tokenExpire.toSeconds), secretKey, algorithm)
    val refresh = Jwt.encode(JwtClaim(refreshJson).issuedNow.expiresIn(refreshTokenExpire.toSeconds), secretKey, algorithm)

    OAuth2JwtToken("Bearer", access, tokenExpire.toSeconds, refresh, refreshTokenExpire.toSeconds)
  }

  def issueJwt(claim: OAuth2Claim): Directive1[OAuth2JwtToken] = {
    val refreshClaim = OAuth2RefreshClaim(claim.identifier)
    provide(issueJwt0(claim, refreshClaim))
  }

  def issueJwt(claim: OAuth2Claim, refreshClaim: OAuth2RefreshClaim): Directive1[OAuth2JwtToken] = {
    provide(issueJwt0(claim, refreshClaim))
  }

  def refreshToken(refreshTokenString: String): Option[OAuth2RefreshClaim] = {
    Jwt.decode(refreshTokenString, secretKey, JwtAlgorithm.allHmac()) match {
      case Success(jwtClaim) =>
        parse(jwtClaim.toJson) match {
          case Right(json) =>
            json.as[OAuth2RefreshClaim] match {
              case Right(refreshToken) =>
                Option(refreshToken)
              case Left(ex) =>
                logger.debug("JWT Refresh Token decoding failure", ex)
                None
            }
          case Left(ex) =>
            logger.debug("JWT Refresh Token parsing failure", ex)
            None
        }
      case _ =>
        logger.debug("Jwt Refresh failure: unable to decode")
        None
    }
  }

  def reissueJwt(claim: OAuth2Claim, refreshTokenString: String): Directive1[OAuth2JwtToken] = {
    Jwt.decode(refreshTokenString, secretKey, JwtAlgorithm.allHmac()) match {
      case Success(jwtClaim) =>
        parse(jwtClaim.toJson) match {
          case Right(json) =>
            json.as[OAuth2RefreshClaim] match {
              case Right(refreshClaim) =>
                if (claim.identifier == refreshClaim.identifier) {
                  val newJwt = issueJwt0(claim, refreshClaim)
                  provide(newJwt)
                } else {
                  logger.debug("Jwt Re-issue rejected: identifier of refresh token does not match")
                  reject(AuthorizationFailedRejection)
                }
              case Left(ex) =>
                logger.debug("Jwt refresh token decoding failure", ex)
                reject(AuthorizationFailedRejection)
            }
          case Left(ex) =>
            logger.debug("Jwt refresh token parsing failure", ex)
            reject(AuthorizationFailedRejection)
        }
      case Failure(ex) =>
        logger.debug("Jwt Re-issue failure", ex)
        reject(AuthorizationFailedRejection)
    }
  }
}
