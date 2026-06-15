package gg.grounds.runtime

interface GroundsModule {
    val id: String

    fun install(ctx: GroundsServerContext)

    fun start() {}

    fun stop() {}
}
