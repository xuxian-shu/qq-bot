package com.qqbot.moderation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qqbot.entity.SensitiveWord;
import com.qqbot.mapper.SensitiveWordMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 敏感词匹配：词库存 MySQL，启动时加载进内存，之后定时刷新。
 * <p>
 * 每条消息只在内存里做一次 O(词数) 的 contains，不查数据库。
 * 单机 1000 人群这个量级完全够用，也符合“拒绝过度设计”的原则。
 */
@Component
public class SensitiveWordFilter {

    private static final Logger log = LoggerFactory.getLogger(SensitiveWordFilter.class);

    // 去掉 [CQ:at,qq=123] 这类 CQ 码，避免匹配到图片 URL、@ 里的数字
    private static final Pattern CQ_CODE = Pattern.compile("\\[CQ:[^]]*]");
    // 零宽字符是常见的绕过手段
    private static final Pattern ZERO_WIDTH = Pattern.compile("[\\u200B-\\u200F\\uFEFF]");
    // 只保留字母和数字（中文属于字母），空格、标点、符号全部丢掉
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

    @Resource
    private SensitiveWordMapper sensitiveWordMapper;

    /** 归一化后的词库，整体替换引用，读写无需加锁 */
    private volatile List<String> words = List.of();

    @PostConstruct
    public void init() {
        reload();
    }

    /**
     * 定时重载词库：在数据库里增删敏感词后最多 1 分钟生效，不用重启机器人。
     * 加载失败时保留旧词库，避免数据库抖一下就把风控清零。
     */
    @Scheduled(fixedDelayString = "${moderation.reload-ms:60000}")
    public void reload() {
        try {
            List<SensitiveWord> rows = sensitiveWordMapper.selectList(
                    new LambdaQueryWrapper<SensitiveWord>()
                            .eq(SensitiveWord::getEnabled, 1));

            List<String> loaded = rows.stream()
                    .map(SensitiveWord::getWord)
                    .map(this::normalize)
                    .filter(word -> !word.isEmpty())
                    .distinct()
                    .toList();

            // 只有内容变化时才打日志，避免每分钟刷一条
            if (!loaded.equals(this.words)) {
                log.info("敏感词库已刷新：{} 条", loaded.size());
            }
            this.words = loaded;
        } catch (Exception e) {
            log.error("敏感词库加载失败，继续使用旧词库（{} 条）", words.size(), e);
        }
    }

    /**
     * @return 命中的敏感词；没命中返回 null
     */
    public String findHit(String message) {
        String text = normalize(message);
        if (text.isEmpty()) {
            return null;
        }
        for (String word : words) {
            if (text.contains(word)) {
                return word;
            }
        }
        return null;
    }

    /**
     * 归一化：NFKC 全角转半角 → 去 CQ 码 → 去零宽 → 只留字母数字 → 转小写。
     * 这样“色 情”“ＳＥＸ”“f.u.c.k”都会归一到同一形态。
     */
    private String normalize(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String text = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        text = CQ_CODE.matcher(text).replaceAll("");
        text = ZERO_WIDTH.matcher(text).replaceAll("");
        text = NON_WORD.matcher(text).replaceAll("");
        return text.toLowerCase(Locale.ROOT);
    }
}