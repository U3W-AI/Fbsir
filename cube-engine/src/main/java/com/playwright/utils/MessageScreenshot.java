package com.playwright.utils;

import com.alibaba.fastjson.JSONObject;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.Graphics2D;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.playwright.utils.ScreenshotUtil.uploadFile;

public class MessageScreenshot {

    public String captureMessagesAsLongScreenshot(Page page, String uploadUrl, String userId) {
        String shareImgUrl = "";
        List<Path> tempImagePaths = new ArrayList<>();
        Path finalScreenshotPath = null;
        ViewportSize originalViewport = null;

        try {
            // 保存原始视口大小
            originalViewport = page.viewportSize();

            // 第一步：隐藏或移除可能遮挡内容的固定元素（如输入框）
            hideFixedElements(page);

            // 定位所有消息元素
            Locator messageElements = page.locator(".ds-message");
            int messageCount = messageElements.count();

            if (messageCount == 0) {
                // 没有找到消息，使用全屏截图作为兜底
                return captureFullPageScreenshot(page, uploadUrl);
            }

            // 截图每条消息并保存到临时文件
            for (int i = 0; i < messageCount; i++) {
                Locator message = messageElements.nth(i);

                // 确保消息元素在视口中可见
                message.scrollIntoViewIfNeeded();
                page.waitForTimeout(500); // 增加等待时间确保内容加载

                // 获取消息元素的完整尺寸（包括滚动内容）
                ElementHandle elementHandle = message.elementHandle();

                // 使用 Map 来接收 evaluate 的结果
                Map<String, Object> sizeInfo = (Map<String, Object>) page.evaluate("""
                    (element) => {
                        // 保存原始样式
                        const originalStyles = {
                            height: element.style.height,
                            maxHeight: element.style.maxHeight,
                            overflow: element.style.overflow
                        };
                        
                        // 临时修改样式以确保完整内容可见
                        element.style.height = 'auto';
                        element.style.maxHeight = 'none';
                        element.style.overflow = 'visible';
                        
                        // 获取完整尺寸
                        const rect = element.getBoundingClientRect();
                        const scrollHeight = element.scrollHeight;
                        const scrollWidth = element.scrollWidth;
                        
                        // 恢复原始样式
                        element.style.height = originalStyles.height;
                        element.style.maxHeight = originalStyles.maxHeight;
                        element.style.overflow = originalStyles.overflow;
                        
                        // 增加边距，确保内容不被截断
                        const padding = 20; // 左右各增加20像素
                        const bottomMargin = 120; // 底部增加120像素
                        
                        return {
                            x: Math.max(0, rect.x - padding / 2),
                            y: rect.y,
                            width: Math.max(rect.width, scrollWidth) + padding,
                            height: Math.max(rect.height, scrollHeight) + bottomMargin,
                            fullHeight: scrollHeight,
                            fullWidth: scrollWidth
                        };
                    }
                """, elementHandle);

                // 安全地从 Map 中提取值，处理 Integer 和 Double 类型
                double x = getDoubleValue(sizeInfo, "x");
                double y = getDoubleValue(sizeInfo, "y");
                double width = getDoubleValue(sizeInfo, "width");
                double height = getDoubleValue(sizeInfo, "height");

                // 创建临时文件路径
                Path screenshotPath = Paths.get(System.getProperty("java.io.tmpdir"),
                        "message_" + i + "_" + UUID.randomUUID() + ".png");

                // 调整视口以确保完整内容可见
                int neededHeight = (int) Math.ceil(height);
                int neededWidth = (int) Math.ceil(width);

                // 临时调整视口大小
                page.setViewportSize(
                        Math.max(originalViewport.width, neededWidth + 100),
                        Math.max(originalViewport.height, neededHeight + 100)
                );

                // 等待视口调整完成
                page.waitForTimeout(300);

                // 重新获取元素位置（视口调整后可能变化）
                Map<String, Object> elementRect = (Map<String, Object>) page.evaluate("""
                    (element) => {
                        const rect = element.getBoundingClientRect();
                        return {
                            x: rect.x,
                            y: rect.y,
                            width: rect.width,
                            height: rect.height
                        };
                    }
                """, elementHandle);

                double finalX = getDoubleValue(elementRect, "x");
                double finalY = getDoubleValue(elementRect, "y");
                double finalW = getDoubleValue(elementRect, "width");
                double finalH = getDoubleValue(elementRect, "height");

                // 确保截图区域不会超出页面边界
                Object pageSize = page.evaluate("() => ({ width: document.documentElement.scrollWidth, height: document.documentElement.scrollHeight })");
                Map<String, Object> pageSizeMap = (Map<String, Object>) pageSize;
                double pageWidth = getDoubleValue(pageSizeMap, "width");
                double pageHeight = getDoubleValue(pageSizeMap, "height");

                // 调整截图区域，确保不超出页面边界
                double clipX = Math.max(0, finalX);
                double clipY = Math.max(0, finalY);
                double clipWidth = Math.min(finalW, pageWidth - clipX);
                double clipHeight = Math.min(finalH, pageHeight - clipY);

                // 截图当前消息（确保完整内容）
                page.screenshot(new Page.ScreenshotOptions()
                        .setPath(screenshotPath)
                        .setClip(clipX, clipY, clipWidth, clipHeight));

                tempImagePaths.add(screenshotPath);

                // 恢复原始视口大小
                page.setViewportSize(originalViewport.width, originalViewport.height);
            }

            // 将所有消息截图拼接成一张长图
            finalScreenshotPath = Paths.get(System.getProperty("java.io.tmpdir"),
                    "combined_" + UUID.randomUUID() + ".png");

            combineImagesVertically(tempImagePaths, finalScreenshotPath);

            // 上传最终的长图
            String result = uploadFile(uploadUrl, finalScreenshotPath.toString());
            JSONObject jsonObject = JSONObject.parseObject(result);
            shareImgUrl = jsonObject.getString("url");

        } catch (Exception e) {
            System.err.println("消息截图失败: " + e.getMessage());
            e.printStackTrace();
            // 尝试使用备用方案
            shareImgUrl = captureFullPageScreenshot(page, uploadUrl);
        } finally {
            // 恢复原始视口大小（确保即使出错也恢复）
            if (originalViewport != null) {
                page.setViewportSize(originalViewport.width, originalViewport.height);
            }
            // 恢复可能被隐藏的元素
            restoreFixedElements(page);
            // 清理临时文件
            cleanupTempFiles(tempImagePaths, finalScreenshotPath);
        }

        return shareImgUrl;
    }

    /**
     * 隐藏可能遮挡内容的固定元素（如输入框）
     */
    private void hideFixedElements(Page page) {
        try {
            page.evaluate("""
                () => {
                    // 保存原始样式以便恢复
                    window._originalFixedElementStyles = {};
                    
                    // 查找所有可能遮挡内容的固定定位元素
                    const fixedElements = document.querySelectorAll('[class*="fixed"], [class*="sticky"], [style*="fixed"], [style*="sticky"]');
                    
                    fixedElements.forEach((el, index) => {
                        // 检查元素是否在底部（可能是输入框）
                        const rect = el.getBoundingClientRect();
                        if (rect.bottom > window.innerHeight - 100) { // 底部100像素内的元素
                            window._originalFixedElementStyles[`element_${index}`] = {
                                element: el,
                                display: el.style.display,
                                visibility: el.style.visibility,
                                position: el.style.position
                            };
                            
                            // 隐藏元素
                            el.style.display = 'none';
                            el.style.visibility = 'hidden';
                        }
                    });
                    
                    return Object.keys(window._originalFixedElementStyles).length;
                }
            """);
        } catch (Exception e) {
            System.err.println("隐藏固定元素失败: " + e.getMessage());
        }
    }

    /**
     * 恢复被隐藏的固定元素
     */
    private void restoreFixedElements(Page page) {
        try {
            page.evaluate("""
                () => {
                    if (window._originalFixedElementStyles) {
                        Object.values(window._originalFixedElementStyles).forEach(styleInfo => {
                            if (styleInfo.element && styleInfo.element.style) {
                                styleInfo.element.style.display = styleInfo.display;
                                styleInfo.element.style.visibility = styleInfo.visibility;
                                styleInfo.element.style.position = styleInfo.position;
                            }
                        });
                        delete window._originalFixedElementStyles;
                    }
                }
            """);
        } catch (Exception e) {
            System.err.println("恢复固定元素失败: " + e.getMessage());
        }
    }

    /**
     * 安全地从 Map 中获取 double 值，处理 Integer 和 Double 类型
     */
    private double getDoubleValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Integer) {
            return ((Integer) value).doubleValue();
        } else if (value instanceof Double) {
            return (Double) value;
        } else if (value instanceof Number) {
            return ((Number) value).doubleValue();
        } else {
            throw new IllegalArgumentException("无法将值转换为 double: " + value);
        }
    }

    /**
     * 将多张图片垂直拼接成一张长图
     */
    private void combineImagesVertically(List<Path> imagePaths, Path outputPath) throws Exception {
        if (imagePaths.isEmpty()) {
            throw new IllegalArgumentException("没有图片可拼接");
        }

        List<BufferedImage> images = new ArrayList<>();
        int totalHeight = 0;
        int maxWidth = 0;

        // 读取所有图片并计算总高度和最大宽度
        for (Path path : imagePaths) {
            BufferedImage img = ImageIO.read(path.toFile());
            images.add(img);
            totalHeight += img.getHeight();
            maxWidth = Math.max(maxWidth, img.getWidth());
        }

        // 创建新的空白图片，增加边距
        int padding = 20; // 左右边距
        BufferedImage combined = new BufferedImage(maxWidth + padding * 2, totalHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = combined.createGraphics();

        // 设置背景色（白色）
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, maxWidth + padding * 2, totalHeight);

        // 将每张图片绘制到合适的位置
        int currentY = 0;
        for (BufferedImage img : images) {
            // 居中放置图片，增加左右边距
            int x = padding;
            g.drawImage(img, x, currentY, null);
            currentY += img.getHeight();

            // 添加分隔线（可选）
            if (currentY < totalHeight) {
                g.setColor(java.awt.Color.LIGHT_GRAY);
                g.drawLine(0, currentY, maxWidth + padding * 2, currentY);
                currentY += 2; // 分隔线高度
            }
        }

        g.dispose();

        // 保存拼接后的图片
        ImageIO.write(combined, "png", outputPath.toFile());
    }

    /**
     * 全屏截图作为备用方案
     */
    private String captureFullPageScreenshot(Page page, String uploadUrl) {
        Path screenshotPath = null;

        try {
            // 先隐藏可能遮挡内容的元素
            hideFixedElements(page);

            screenshotPath = Paths.get(System.getProperty("java.io.tmpdir"),
                    "fullpage_" + UUID.randomUUID() + ".png");

            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(screenshotPath)
                    .setFullPage(true));

            String result = uploadFile(uploadUrl, screenshotPath.toString());
            JSONObject jsonObject = JSONObject.parseObject(result);
            return jsonObject.getString("url");

        } catch (Exception e) {
            System.err.println("全屏截图也失败了: " + e.getMessage());
            return "";
        } finally {
            // 恢复被隐藏的元素
            restoreFixedElements(page);
            if (screenshotPath != null) {
                try {
                    Files.deleteIfExists(screenshotPath);
                } catch (IOException e) {
                    System.err.println("删除临时文件失败: " + e.getMessage());
                }
            }
        }
    }

    /**
     * 清理临时文件
     */
    private void cleanupTempFiles(List<Path> tempImagePaths, Path finalScreenshotPath) {
        // 删除所有临时消息截图
        for (Path path : tempImagePaths) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                System.err.println("删除临时文件失败: " + path.toString() + ": " + e.getMessage());
            }
        }

        // 删除最终拼接的图片（如果已上传）
        if (finalScreenshotPath != null) {
            try {
                Files.deleteIfExists(finalScreenshotPath);
            } catch (IOException e) {
                System.err.println("删除最终截图失败: " + finalScreenshotPath.toString() + ": " + e.getMessage());
            }
        }
    }

}