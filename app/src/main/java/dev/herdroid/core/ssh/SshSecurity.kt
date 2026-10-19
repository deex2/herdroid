package dev.herdroid.core.ssh

import com.hierynomus.sshj.key.KeyAlgorithms
import net.schmizz.sshj.AndroidConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.keepalive.KeepAliveRunner

internal fun configureSshSecurity() = synchronized(SecurityUtils::class.java) {
    SecurityUtils.setRegisterBouncyCastle(false)
    SecurityUtils.setSecurityProvider(null)
}

internal fun productionSshConfig(): AndroidConfig {
    configureSshSecurity()
    return AndroidConfig().apply {
        keepAliveProvider = KeepAliveProvider.KEEP_ALIVE
        keyAlgorithms = keyAlgorithms + KeyAlgorithms.ECDSASHANistp256()
    }
}

internal fun productionSshClient() = SSHClient(productionSshConfig()).apply {
    (connection.keepAlive as KeepAliveRunner).maxAliveCount = 3
}
