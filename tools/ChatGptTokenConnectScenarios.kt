package com.example.airemote

import java.net.UnknownHostException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException

object ChatGptTokenConnectScenarios {
    @JvmStatic fun main(args: Array<String>) {
        var assertions = 0
        fun case(run: () -> Unit) { run(); assertions++ }
        case { var opened=0; var connected=0
            val selected=ChatGptTokenConnect.openBeforeBody(create={ ++opened }, establish={ connected++; check(it==1) }, discard={ error("unexpected discard") }, pauseBeforeRetry={ error("unexpected retry") })
            check(selected==1 && opened==1 && connected==1)
        }
        case { var opened=0; var connected=0; var closed=0; var paused=0
            val selected=ChatGptTokenConnect.openBeforeBody(create={ ++opened }, establish={ connected++; if (it==1) throw UnknownHostException() }, discard={ closed++ }, pauseBeforeRetry={ paused++ })
            check(selected==2 && opened==2 && connected==2 && closed==1 && paused==1)
        }
        case { var opened=0; var closed=0
            try { ChatGptTokenConnect.openBeforeBody(create={ ++opened }, establish={ throw UnknownHostException() }, discard={ closed++ }, pauseBeforeRetry={}) ; error("must fail") }
            catch (_: UnknownHostException) { check(opened==2 && closed==2) }
        }
        case { var opened=0; var closed=0
            try { ChatGptTokenConnect.openBeforeBody(create={ ++opened }, establish={ throw SSLException("failed") }, discard={ closed++ }, pauseBeforeRetry={ error("never retry TLS") }) ; error("must fail") }
            catch (_: SSLException) { check(opened==1 && closed==1) }
        }
        case { var opened=0; var closed=0
            try { ChatGptTokenConnect.openBeforeBody(create={ ++opened }, establish={ throw SocketTimeoutException("timeout") }, discard={ closed++ }, pauseBeforeRetry={ error("never retry timeout") }); error("must fail") }
            catch (_: SocketTimeoutException) { check(opened==1 && closed==1) }
        }
        case { var sent=0; var opened=0
            val selected=ChatGptTokenConnect.openBeforeBody(create={++opened}, establish={ if(it==1) throw UnknownHostException() }, discard={}, pauseBeforeRetry={})
            // The caller writes the POST body only after a connection is returned.
            sent++; check(selected==2 && sent==1 && opened==2)
        }
        println("CHATGPT_TOKEN_CONNECT_OK=$assertions")
    }
}
