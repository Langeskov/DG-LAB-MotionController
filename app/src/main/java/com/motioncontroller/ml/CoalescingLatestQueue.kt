package com.motioncontroller.ml

/**
 * 深度 1 的「合并最新任务」队列（纯 Kotlin，无 Android 依赖，可 JVM 单测）。
 *
 * 推理任务（特征 + ORT）偶尔会超过 1s（慢设备 / CPU 回退）。旧实现用无界单线程
 * executor 投递，任务一旦堆积就会延迟越来越大，而且结果早已过期。这里改成
 * **只保留最新一份**：新任务覆盖尚未开始的旧任务（drop oldest），且最多只有一个
 * drain 任务在排队，队列深度恒为 1。
 *
 * 使用契约（见 [MotionPipeline]）：
 * ```
 * if (queue.offer(job)) executor.execute { drain() }
 *
 * fun drain() {
 *     while (running) {
 *         val job = queue.poll() ?: if (queue.exitDrain()) return else continue
 *         run(job)
 *     }
 *     queue.clear()
 *     while (!queue.exitDrain()) queue.clear()
 * }
 * ```
 * `exitDrain()` 在锁内同时检查 pending 与清除 scheduled，避免 poll→exitDrain 之间
 * 新 offer 被"漏掉"（offer 看到 scheduled=true 不会重新投递 drain 任务）。
 */
class CoalescingLatestQueue<T> {

    private val lock = Any()
    private var pending: T? = null
    private var drainScheduled = false

    /**
     * 放入最新任务。
     *
     * @return true 表示调用方需要向 executor 投递一个 drain 任务；false 表示已有 drain
     *         任务在排队/运行，它会把这份新任务取走。
     */
    fun offer(item: T): Boolean = synchronized(lock) {
        pending = item
        if (drainScheduled) {
            false
        } else {
            drainScheduled = true
            true
        }
    }

    /** 取出并清空当前最新任务；没有则返回 null。 */
    fun poll(): T? = synchronized(lock) {
        val item = pending
        pending = null
        item
    }

    /**
     * 尝试结束 drain。
     *
     * @return true = 当前无 pending 且已清除 scheduled（drain 任务可以退出）；
     *         false = 期间又有新任务，调用方应继续 drain。
     */
    fun exitDrain(): Boolean = synchronized(lock) {
        if (pending != null) {
            false
        } else {
            drainScheduled = false
            true
        }
    }

    /** 丢弃未处理任务（stop 时调用）；不会改变 drain 调度状态。 */
    fun clear() {
        synchronized(lock) { pending = null }
    }

    /** 当前是否有一个待处理任务（测试/诊断用）。 */
    val hasPending: Boolean get() = synchronized(lock) { pending != null }
}
