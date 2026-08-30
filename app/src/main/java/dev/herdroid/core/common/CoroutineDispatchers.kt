package dev.herdroid.core.common

import javax.inject.Qualifier

enum class HerdroidDispatchers { IO, Default }

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class Dispatcher(val dispatcher: HerdroidDispatchers)
