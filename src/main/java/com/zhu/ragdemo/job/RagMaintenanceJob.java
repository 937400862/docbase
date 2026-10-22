package com.zhu.ragdemo.job;

import com.zhu.ragdemo.service.RagMaintenanceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 知识库体检定时任务（2026-10-22 新增）。
 *
 * <p>两个任务，默认都**关闭**（`rag.maintenance.enabled=false`），需要时在启动参数里打开：
 * <pre>
 * --rag.maintenance.enabled=true
 * --rag.maintenance.report-cron="0 30 3 * * ?"   # 每天 3:30 体检（只读）
 * --rag.maintenance.fix-cron="0 30 4 * * ?"      # 每天 4:30 修残缺
 * --rag.maintenance.fix-limit=50                 # 单次最多修 50 个，防止跑一整天
 * </pre>
 *
 * <p>为什么默认关闭：体检要扫 6 万多个键，修复要重新解析+嵌入（每个文件几十秒），
 * 在只有 1.78 GB 内存的机器上跑之前，得先确认当下没有别的摄入任务在跑。
 *
 * <p>业务逻辑全在 {@link RagMaintenanceService} 里，本类只负责"什么时候调"。
 *
 * @author ：zhuzj（2026-10-22 新增）
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "rag.maintenance.enabled", havingValue = "true")
public class RagMaintenanceJob {

    private final RagMaintenanceService maintenanceService;

    /** 单次最多修几个残缺文件 */
    @Value("${rag.maintenance.fix-limit:50}")
    private int fixLimit;

    /** 体检时是否顺带查重复文件（要读文件内容，比较慢，默认开） */
    @Value("${rag.maintenance.check-duplicates:true}")
    private boolean checkDuplicates;

    public RagMaintenanceJob(RagMaintenanceService maintenanceService) {
        this.maintenanceService = maintenanceService;
    }

    /**
     * 每日体检：孤儿 / 残缺 / 重复，**只报告不修改**。
     */
    @Scheduled(cron = "${rag.maintenance.report-cron:0 30 3 * * ?}")
    public void reportJob() {
        log.info("=== [定时任务] 知识库体检开始 ===");
        try {
            maintenanceService.check(checkDuplicates);
        } catch (Throwable t) {
            // 定时任务里必须兜住所有异常，否则 Spring 的调度线程会把这个任务停掉
            log.error("[定时任务] 体检失败: {}", t.toString(), t);
        }
        log.info("=== [定时任务] 知识库体检结束 ===");
    }

    /**
     * 每日修复：把残缺文件删掉重新摄入。
     *
     * <p>放在体检之后半小时，避免两个任务同时扫库。
     */
    @Scheduled(cron = "${rag.maintenance.fix-cron:0 30 4 * * ?}")
    public void fixJob() {
        log.info("=== [定时任务] 残缺文件修复开始（上限 {} 个）===", fixLimit);
        try {
            int fixed = maintenanceService.fixIncomplete(maintenanceService.scanIndexed(), fixLimit);
            log.info("=== [定时任务] 修复完成，本次修复 {} 个 ===", fixed);
        } catch (Throwable t) {
            log.error("[定时任务] 修复失败: {}", t.toString(), t);
        }
    }
}
