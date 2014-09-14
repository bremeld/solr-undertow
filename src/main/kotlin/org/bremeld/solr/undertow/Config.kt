//    Copyright 2014 Bremeld Corp SA (Montevideo, Uruguay)
//    https://www.linkedin.com/company/bremeld
//
//    Licensed under the Apache License, Version 2.0 (the "License");
//    you may not use this file except in compliance with the License.
//    You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
//    Unless required by applicable law or agreed to in writing, software
//    distributed under the License is distributed on an "AS IS" BASIS,
//    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//    See the License for the specific language governing permissions and
//    limitations under the License.

package org.bremeld.solr.undertow

import kotlin.reflect.KMemberProperty
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.slf4j.Logger
import java.io.File
import java.nio.file.Files
import java.util.HashMap
import java.util.Properties

private val SOLR_UNDERTOW_CONFIG_PREFIX = "solr.undertow"

private val SYS_PROP_JETTY_PORT = "jetty.port"
private val OUR_PROP_HTTP_PORT = "httpClusterPort"

private val SYS_PROP_ZKRUN = "zkRun"
private val OUR_PROP_ZKRUN = "zkRun"

private val SYS_PROP_ZKHOST = "zkHost"
private val OUR_PROP_ZKHOST = "zkHost"

private val SYS_PROP_SOLRLOG = "solr.log"
private val OUR_PROP_SOLRLOG = "solrLogs"

private val SYS_PROP_HOSTCONTEXT = "hostContext"
private val OUR_PROP_HOSTCONTEXT = "solrContextPath"

private val SYS_PROP_SOLRHOME = "solr.solr.home"
private val OUR_PROP_SOLRHOME = "solrHome"

// system and environment variables that need to be treated the same as our configuration items (excluding zkRun)
private val SOLR_OVERRIDES = mapOf(SYS_PROP_JETTY_PORT to OUR_PROP_HTTP_PORT,
        SYS_PROP_ZKHOST to OUR_PROP_ZKHOST,
        SYS_PROP_SOLRLOG to OUR_PROP_SOLRLOG,
        SYS_PROP_HOSTCONTEXT to OUR_PROP_HOSTCONTEXT,
        SYS_PROP_SOLRHOME to OUR_PROP_SOLRHOME)

private class ServerConfigLoader(val configFile: File) {
    private val solrOverrides = run {
        // copy values from typical Solr system or environment properties into our equivalent configuration item
        val p = Properties()
        for (mapping in SOLR_OVERRIDES.entrySet()) {
            val value = System.getProperty(mapping.key) ?: System.getenv(mapping.key)
            if (value != null) {
                p.put("${SOLR_UNDERTOW_CONFIG_PREFIX}.${mapping.value}", value)
            }
        }
        if (System.getProperty(SYS_PROP_ZKRUN) ?: System.getenv(SYS_PROP_ZKRUN) != null) {
            p.put("${SOLR_UNDERTOW_CONFIG_PREFIX}.${OUR_PROP_ZKRUN}", "true")
        }
        ConfigFactory.parseProperties(p)!!
    }

    val resolvedConfig = solrOverrides.withFallback(ConfigFactory.systemProperties())!!
            .withFallback(ConfigFactory.systemEnvironment())!!
            .withFallback(ConfigFactory.parseFile(configFile)!!)!!
            .withFallback(ConfigFactory.defaultReference()!!)!!.resolve()!!
            .then { config ->
                // copy our configuration items into Solr system properties that might be looked for later by Solr
                for (mapping in SOLR_OVERRIDES.entrySet()) {
                    val configValue = config.getString("${SOLR_UNDERTOW_CONFIG_PREFIX}.${mapping.value}")!!.trim()
                    if (configValue.isNotEmpty()) {
                        System.setProperty(mapping.key, configValue)
                    }
                }
                if (config.getBoolean("${SOLR_UNDERTOW_CONFIG_PREFIX}.${OUR_PROP_ZKRUN}")) {
                    System.setProperty(SYS_PROP_ZKRUN, "true")
                }

                // an extra system property to set
                System.setProperty("org.jboss.logging.provider", "slf4j")

            }

    fun hasLoggingDir(): Boolean {
       return resolvedConfig.hasPath("${SOLR_UNDERTOW_CONFIG_PREFIX}.${OUR_PROP_SOLRLOG}")
    }
}

private class ServerConfig(private val log: Logger, private val loader: ServerConfigLoader) {
    val cfg = loader.resolvedConfig.getConfig(SOLR_UNDERTOW_CONFIG_PREFIX)!!
    val httpClusterPort = cfg.getInt(OUR_PROP_HTTP_PORT)
    val httpHost = cfg.getString("httpHost")!!
    val httpIoThreads = Math.max(cfg.getInt("httpIoThreads"), 0)
    val httpWorkerThreads = Math.max(cfg.getInt("httpWorkerThreads"), 0)
    val activeRequestLimits = cfg.getStringList("activeRequestLimits")!!.copyToArray()
    val requestLimiters = HashMap<String, RequestLimitConfig>() initializedBy { requestLimiters ->
        val namedConfigs = cfg.getConfig("requestLimits")!!
        activeRequestLimits.forEach { name ->
            requestLimiters.put(name, RequestLimitConfig(log, name, namedConfigs.getConfig(name)!!))
        }
    }
    val zkRun = cfg.getBoolean(OUR_PROP_ZKRUN)
    val zkHost = cfg.getString(OUR_PROP_ZKHOST)!!
    val solrHome = File(cfg.getString(OUR_PROP_SOLRHOME)!!)
    val solrLogs = File(cfg.getString(OUR_PROP_SOLRLOG)!!)
    val tempDir = File(cfg.getString("tempDir")!!)
    val solrVersion = cfg.getString("solrVersion")!!
    val solrWarFile = File(cfg.getString("solrWarFile")!!)
    val libExtDirName = cfg.getString("libExtDir")!!.trim()
    val libExtDir = File(libExtDirName)
    val solrContextPath = cfg.getString(OUR_PROP_HOSTCONTEXT)!!.trim() let { solrContextPath ->
        if (solrContextPath.isEmpty()) "/" else solrContextPath
    }

    fun hasLibExtDir(): Boolean = libExtDirName.isNotEmpty()

    private fun printF(p: KMemberProperty<ServerConfig, File>) = log.info("  ${p.name}: ${p.get(this).getAbsolutePath()}")
    private fun printS(p: KMemberProperty<ServerConfig, String>) = log.info("  ${p.name}: ${p.get(this)}")
    private fun printSA(p: KMemberProperty<ServerConfig, Array<String>>) = log.info("  ${p.name}: ${p.get(this).joinToString(",")}")
    private fun printB(p: KMemberProperty<ServerConfig, Boolean>) = log.info("  ${p.name}: ${p.get(this).toString()}")
    private fun printI(p: KMemberProperty<ServerConfig, Int>) = log.info("  ${p.name}: ${p.get(this).toString()}")

    fun print() {
        log.info("=== [ Config File settings from: ${loader.configFile.getAbsolutePath()} ] ===")
        printB(::zkRun)
        printS(::zkHost)
        printI(::httpClusterPort)
        printS(::httpHost)
        printI(::httpIoThreads)
        printI(::httpWorkerThreads)

        printSA(::activeRequestLimits)

        requestLimiters.values().forEach { rl ->
            rl.print()
        }

        printF(::solrHome)
        printF(::solrLogs)
        printF(::tempDir)
        printS(::solrVersion)
        printF(::solrWarFile)
        printS(::solrContextPath)
        if (hasLibExtDir()) {
            printF(::libExtDir)
        }
        if (log.isDebugEnabled()) {
            log.debug("<<<< CONFIGURATION FILE TRACE >>>>")
            log.debug(cfg.root()!!.render())
        }
        log.info("=== [ END CONFIG ] ===")


    }

    fun validate(): Boolean {
        log.info("Validating configuration from: ${loader.configFile.getAbsolutePath()}")
        var isValid = true
        fun err(msg: String) {
            log.error(msg)
            isValid = false
        }

        fun existsIsWriteable(p: KMemberProperty<ServerConfig, File>) {
            val dir = p.get(this)
            if (!dir.exists()) {
                err("${p.name} dir does not exist: ${dir.getAbsolutePath()}")
            }
            if (!Files.isWritable(dir.toPath()!!)) {
                err("${p.name} dir must be writable by current user: ${dir.getAbsolutePath()}")
            }
        }

        fun existsIsReadable(p: KMemberProperty<ServerConfig, File>) {
            val dir = p.get(this)
            if (!dir.exists()) {
                err("${p.name} does not exist: ${dir.getAbsolutePath()}")
            }
            if (!Files.isReadable(dir.toPath()!!)) {
                err("${p.name} must be readable by current user: ${dir.getAbsolutePath()}")
            }
        }

        requestLimiters.values().forEach { rl ->
            rl.validate()
        }

        existsIsWriteable(::solrHome)
        existsIsWriteable(::solrLogs)
        existsIsWriteable(::tempDir)
        existsIsReadable(::solrWarFile)

        if (hasLibExtDir()) {
            existsIsReadable(::libExtDir)
        }

        return isValid
    }
}


private class RequestLimitConfig(private val log: Logger, val name: String, private val cfg: Config) {
    val exactPaths = if (cfg.hasPath("exactPaths")) cfg.getStringList("exactPaths")!! else listOf<String>()
    val pathSuffixes = if (cfg.hasPath("pathSuffixes")) cfg.getStringList("pathSuffixes")!! else listOf<String>()
    val concurrentRequestLimit = Math.max(cfg.getInt("concurrentRequestLimit"), -1)
    val maxQueuedRequestLimit = Math.max(cfg.getInt("maxQueuedRequestLimit"), -1)


    fun validate(): Boolean {
        if (exactPaths.isEmpty() && pathSuffixes.isEmpty()) {
            log.error("${name}: exactPaths AND/OR pathSuffixes is required in rate limitter")
            return false
        }
        return true
    }

    fun print() {
        log.info("  ${name} >>")
        log.info("    exactPaths: ${exactPaths.joinToString(",")}")
        log.info("    pathSuffixes: ${pathSuffixes.joinToString(",")}")
        log.info("    concurrentRequestLimit: ${if (concurrentRequestLimit < 0) "unlimited" else Math.min(concurrentRequestLimit, 1) }")
        log.info("    maxQueuedRequestLimit: ${if (maxQueuedRequestLimit < 0) "unlimited" else maxQueuedRequestLimit }")
    }
}


