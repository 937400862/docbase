package com.zhu.docbase.event;

/**
 * 摄入完成事件：有新的 chunk 写进库之后发出来。
 *
 * <p>为什么要走事件而不是直接调用索引服务：
 * 摄入服务不应该知道"检索层有 BM25 索引需要刷新"这件事。用事件解耦之后，
 * 以后再加别的消费者（统计、缓存失效、通知）都不用改摄入代码。
 *
 * <p>只在<b>真的写入了 chunk</b> 时发出。「已摄入过而跳过」「内容过短跳过」「失败」
 * 都不发 —— 否则目录摄入 227 个文件会触发 227 次无意义的重建。
 *
 * @param chunks 本次写入的片段数，恒大于 0
 * @author ：zhuzj
 * @date ：2026/10/23
 */
public record IngestCompletedEvent(int chunks) {
}
