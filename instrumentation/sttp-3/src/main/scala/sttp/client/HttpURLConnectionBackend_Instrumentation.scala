/*
 *
 *  * Copyright 2020 New Relic Corporation. All rights reserved.
 *  * SPDX-License-Identifier: Apache-2.0
 *
 */

package sttp.client

import com.newrelic.api.agent.weaver.Weaver
import com.newrelic.api.agent.weaver.scala.{ScalaMatchType, ScalaWeave}
import com.nr.agent.instrumentation.sttp.DelegateIdentity
import sttp.client3.HttpURLConnectionBackend.EncodingHandler
import sttp.client3.{Identity, SttpBackend, SttpBackendOptions}

import java.net.{HttpURLConnection, URL, URLConnection}

@ScalaWeave(`type` = ScalaMatchType.Object, `originalName` = "sttp.client3.HttpURLConnectionBackend")
class HttpURLConnectionBackend_Instrumentation {
  def apply(options: SttpBackendOptions, customizeConnection: HttpURLConnection => Unit, createURL: String => URL, openConnection: (URL, Option[java.net.Proxy]) => URLConnection, customEncodingHandler: EncodingHandler): SttpBackend[Identity, Any] = new DelegateIdentity(Weaver.callOriginal())
}
