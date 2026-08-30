package dev.herdroid.core.ssh

import com.hierynomus.sshj.key.KeyAlgorithms
import net.schmizz.sshj.AndroidConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.SecurityUtils

internal fun configureSshSecurity() = synchronized(SecurityUtils::class.java) {
    SecurityUtils.setRegisterBouncyCastle(false)
    SecurityUtils.setSecurityProvider(null)
}

internal fun productionSshConfig(): AndroidConfig {
    configureSshSecurity()
    return AndroidConfig().apply {
        keyAlgorithms = keyAlgorithms + KeyAlgorithms.ECDSASHANistp256()
    }
}

internal fun productionSshClient() = SSHClient(productionSshConfig())
