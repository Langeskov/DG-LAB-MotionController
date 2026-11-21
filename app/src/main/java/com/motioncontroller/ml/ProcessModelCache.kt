package com.motioncontroller.ml

/**
 * 进程级模型/参数缓存（纯 Kotlin，无 Android 依赖，可 JVM 单测）。
 *
 * 背景（2026-10-31 audit）：旧实现每次 `startEngine` 都在推理线程上重建 ONNX session
 * 并重新 warm-up；在 XNNPACK warm-up 失败的机型上等于每次都要把 XNNPACK + CPU 两条
 * 失败路径再跑一遍（几百 ms 卡顿 + 原生资源反复申请/释放）。
 *
 * 语义：
 *  - [getOrLoad] 只在首次调用时执行 loader，之后返回同一个实例（assets 是静态的，进程内
 *    不需要重建/重热）；
 *  - 加载失败同样缓存失败原因，避免每次 startEngine 重复付出失败成本（进程重启后会重新尝试）；
 *  - [close] 幂等：从缓存摘下实例后再调用 closer，保证同一个 native session 只关闭一次；
 *    close 之后再次 [getOrLoad] 会重新加载（测试/显式重置用）。
 *
 * 线程：所有状态由内部锁保护；缓存未命中时在锁内执行 loader（每个进程最多一次，可以接受），
 * 避免并发重复加载/重复 close。
 */
class ProcessModelCache<T : Any>(
    /** 便于日志/状态展示的名字（如 assets 文件名）。 */
    private val name: String,
    /** 关闭回调（native session 用）；缓存保证同一个实例只调用一次，回调自身也应幂等。 */
    private val closer: (T) -> Unit = {},
) {

    private val lock = Any()

    private var cached: T? = null
    private var failure: String? = null

    /** 缓存中的实例（可能为 null）。 */
    val valueOrNull: T? get() = synchronized(lock) { cached }

    /** 上次加载失败的描述（无失败为 null）。 */
    val lastFailure: String? get() = synchronized(lock) { failure }

    /** 该实例当前是否由本缓存持有（stop 时用来判断要不要兜底关闭）。 */
    fun owns(instance: T): Boolean = synchronized(lock) { cached === instance }

    /**
     * 返回缓存实例；未加载则执行 [loader]。
     *
     * @return 实例；加载失败（或之前失败已缓存）返回 null，原因见 [lastFailure]
     */
    fun getOrLoad(loader: () -> T): T? {
        synchronized(lock) {
            cached?.let { return it }
            if (failure != null) return null
            return try {
                loader().also {
                    cached = it
                    failure = null
                }
            } catch (t: Throwable) {
                failure = t.message ?: t.javaClass.simpleName
                null
            }
        }
    }

    /**
     * 关闭并清空缓存（幂等）。同一个实例只会交给 closer 一次。
     */
    fun close() {
        val old: T?
        synchronized(lock) {
            old = cached
            cached = null
            failure = null
        }
        if (old != null) runCatching { closer(old) }
    }

    override fun toString(): String =
        "ProcessModelCache($name, cached=${valueOrNull != null}, failure=$lastFailure)"
}
