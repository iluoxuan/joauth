package com.twitter.joauth

import javax.servlet.http.HttpServletRequest
import java.io.ByteArrayOutputStream

class UnpackerException(val message: String, t: Throwable) extends Exception(message, t) {
  def this(message: String) = this(message, null)
}
class UnknownAuthType(message: String) extends UnpackerException(message)
class MalformedRequest(message: String) extends UnpackerException(message)

trait UriSchemeGetter extends ((HttpServletRequest) => String)
trait PathGetter extends ((HttpServletRequest) => String)

object StandardUriSchemeGetter extends UriSchemeGetter {
  def apply(request: HttpServletRequest): String = request.getScheme
}

object StandardPathGetter extends PathGetter {
  def apply(request: HttpServletRequest): String = request.getPathInfo
}

trait Unpacker {
  @throws(classOf[UnpackerException])
  def apply(
      request: HttpServletRequest,
      kvHandlers: Seq[KeyValueHandler]): OAuthRequest
}

object Unpacker {
  def apply(): StandardUnpacker = 
    new StandardUnpacker(
      StandardUriSchemeGetter,
      StandardPathGetter,
      new StandardKeyValueParser("&", "="),
      new StandardKeyValueParser("\\s*,\\s*", "\\s*=\\s*"))

  def apply(
    getScheme: UriSchemeGetter,
    getPath: PathGetter): StandardUnpacker = 
    new StandardUnpacker(
      getScheme,
      getPath,
      new StandardKeyValueParser("&", "="),
      new StandardKeyValueParser("\\s*,\\s*", "\\s*=\\s*"))

  def apply(
      getScheme: UriSchemeGetter,
      getPath: PathGetter,
      queryParser: KeyValueParser,
      headerParser: KeyValueParser): StandardUnpacker =
    new StandardUnpacker(getScheme, getPath, queryParser, headerParser)
}

object StandardUnpacker {
  val AUTH_HEADER_REGEX = """^(\S+)\s+(.*)$""".r
  val POST = "POST"
  val WWW_FORM_URLENCODED = "application/x-www-form-urlencoded"
  val AUTHORIZATION = "Authorization"
  val HTTPS = "HTTPS"
}

class StandardUnpacker(
    getScheme: UriSchemeGetter,
    getPath: PathGetter,
    queryParser: KeyValueParser,
    headerParser: KeyValueParser) extends Unpacker {
  import StandardUnpacker._

  @throws(classOf[UnpackerException])
  def apply(
    request: HttpServletRequest, 
    kvHandlers: Seq[KeyValueHandler]): OAuthRequest = {

    try {
      val (params, oAuthParams) = parseRequest(request, kvHandlers)

      if (oAuthParams.areAllOAuth1FieldsSet) {
        getOAuth1RequestBuilder(request, params, oAuthParams).build
      } else if (oAuthParams.isOnlyOAuthTokenSet) {
        getOAuth2Request(request, oAuthParams.token)
      } else throw new UnknownAuthType("could not determine the authentication type")

    } catch {
      case u:UnpackerException => throw u
      case t:Throwable => throw new UnpackerException("could not unpack request: " + t, t)
    }
  }
  
  def getOAuth1RequestBuilder(
    request: HttpServletRequest,
    params: List[(String, String)],
    oAuthParams: OAuthParams): OAuth1RequestBuilder = {
      val builder = new OAuth1RequestBuilder(params, oAuthParams)
      builder.scheme = getScheme(request).toUpperCase
      builder.verb = request.getMethod.toUpperCase
      builder.host = request.getServerName
      builder.port = request.getServerPort
      builder.path = getPath(request)
      builder
    }

  @throws(classOf[MalformedRequest])
  def getOAuth2Request(
      request: HttpServletRequest, token: String): OAuthRequest = {
    if (getScheme(request).toUpperCase == HTTPS) new OAuth2Request(token)
    else throw new MalformedRequest("OAuth 2.0 requests must use HTTPS")
  }

  def parseRequest(request: HttpServletRequest, kvHandlers: Seq[KeyValueHandler]) = {
    val queryParser = new StandardKeyValueParser("&", "=")

    val kvHandler = new DuplicateKeyValueHandler
    val filteredKvHandler = new NotOAuthKeyValueHandler(kvHandler)

    val oAuthParams = new OAuthParams
    val filteredOAuthKvHandler = new OAuthKeyValueHandler(oAuthParams)
    val handlerSeq = Seq(filteredKvHandler, filteredOAuthKvHandler) ++ kvHandlers.map(h => new NotOAuthKeyValueHandler(h))

    queryParser(request.getQueryString, handlerSeq)

    if (request.getMethod.toUpperCase == POST &&
        request.getContentType != null &&
        request.getContentType.startsWith(WWW_FORM_URLENCODED)) {
      queryParser(getPostData(request), handlerSeq)
    }

    request.getHeader("Authorization") match {
      case AUTH_HEADER_REGEX(authType, authString) => {
        val headerHandler = authType.toLowerCase match {
          case OAuthUtils.OAUTH2_HEADER_AUTHTYPE => Some(new OAuth2HeaderKeyValueHandler(filteredOAuthKvHandler))
          case OAuthUtils.OAUTH1_HEADER_AUTHTYPE => Some(filteredOAuthKvHandler)
          case _ => None
        }
        headerHandler match {
          case Some(handler) => {
            val quotedHandler = new QuotedValueKeyValueHandler(handler)
            headerParser(authString, Seq(quotedHandler))
          }
          case None =>
        }
      }
      case _ =>
    }

    (kvHandler.toList, oAuthParams)
  }

  def getPostData(request: HttpServletRequest) = {
    val is = request.getInputStream
    val stream = new ByteArrayOutputStream()
    val buf = new Array[Byte](4 * 1024)
    var letti = is.read(buf)
    var totalBytesRead = 0

    while (letti > 0) {
      stream.write(buf, 0, letti)
      letti = is.read(buf)
      totalBytesRead += letti
      if (totalBytesRead > request.getContentLength) {
        throw new IllegalStateException("more bytes in input stream than content-length specified")
      }
    }
    val result = new String(stream.toByteArray())
    result
  }
}