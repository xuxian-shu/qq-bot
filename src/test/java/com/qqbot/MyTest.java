package com.qqbot;

import com.qqbot.utils.ImageUtils;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

public class MyTest {

    @Test
    public void testImage() {
        try {
            // 随便填一个真实的 QQ 号（比如你的大号），用来拉取头像
            long testQq = 123456789L;
            byte[] imageBytes = ImageUtils.generateCardBytes(testQq, "测试猫娘", 100, "连击 3 天，本次 +14");
            // 写到 D 盘方便直接看效果
            Files.write(Path.of("D:/test_card.png"), imageBytes);
            System.out.println("图片已生成：D:/test_card.png");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}