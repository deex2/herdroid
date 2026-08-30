package dev.herdroid.core.ssh

import java.io.IOException

class SshAuthenticationFailedException(cause: Throwable) : IOException("SSH authentication failed", cause)
