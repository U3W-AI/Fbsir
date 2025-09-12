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

    /**
     * 只截取最后一个回复容器的完整内容
     */
    public String captureMessagesAsLongScreenshot(Page page, String uploadUrl, String userId) {
        String shareImgUrl = "";
        Path finalScreenshotPath = null;
        ViewportSize originalViewport = null;

        try {
            // 保存原始视口大小
            originalViewport = page.viewportSize();

            // 隐藏可能遮挡内容的固定元素
            hideFixedElements(page);

            // 查找最后一个回复容器
            Map<String, Object> containerInfo = (Map<String, Object>) page.evaluate("""
                () => {
                    try {
                        // 查找所有回复容器
                        const containers = document.querySelectorAll('div._4f9bf79.d7dc56a8._43c05b5');
                        if (containers.length === 0) {
                            return { success: false, message: 'no-containers-found' };
                        }
                        
                        // 获取最后一个容器（最新的回复）
                        const lastContainer = containers[containers.length - 1];
                        
                        // 暂时移除高度限制，获取完整内容高度
                        const originalStyle = {
                            height: lastContainer.style.height,
                            maxHeight: lastContainer.style.maxHeight,
                            overflow: lastContainer.style.overflow
                        };
                        
                        lastContainer.style.height = 'auto';
                        lastContainer.style.maxHeight = 'none';
                        lastContainer.style.overflow = 'visible';
                        
                        // 滚动到容器顶部
                        lastContainer.scrollIntoView({ behavior: 'auto', block: 'start' });
                        
                        // 获取容器的完整尺寸信息
                        const rect = lastContainer.getBoundingClientRect();
                        const scrollHeight = lastContainer.scrollHeight;
                        const scrollWidth = lastContainer.scrollWidth;
                        
                        // 恢复原始样式
                        lastContainer.style.height = originalStyle.height;
                        lastContainer.style.maxHeight = originalStyle.maxHeight;
                        lastContainer.style.overflow = originalStyle.overflow;
                        
                        // 添加一些边距确保内容不被截断
                        const padding = 40;
                        const bottomMargin = 120;
                        
                        return {
                            success: true,
                            x: Math.max(0, rect.x - padding / 2),
                            y: Math.max(0, rect.y - 20),
                            width: Math.max(rect.width, scrollWidth) + padding,
                            height: Math.max(rect.height, scrollHeight) + bottomMargin,
                            scrollHeight: scrollHeight,
                            scrollWidth: scrollWidth,
                            containerCount: containers.length
                        };
                    } catch (e) {
                        return { success: false, message: e.toString() };
                    }
                }
            """);

            if (!Boolean.TRUE.equals(containerInfo.get("success"))) {
                System.err.println("查找最后一个回复容器失败: " + containerInfo.get("message"));
                return captureFullPageScreenshot(page, uploadUrl);
            }

            System.out.println("找到 " + containerInfo.get("containerCount") + " 个回复容器，准备截取最后一个");

            // 获取容器尺寸信息
            double containerX = getDoubleValue(containerInfo, "x");
            double containerY = getDoubleValue(containerInfo, "y");
            double containerWidth = getDoubleValue(containerInfo, "width");
            double containerHeight = getDoubleValue(containerInfo, "height");
            double scrollHeight = getDoubleValue(containerInfo, "scrollHeight");

            System.out.println(String.format("容器尺寸: x=%.0f, y=%.0f, width=%.0f, height=%.0f, scrollHeight=%.0f", 
                containerX, containerY, containerWidth, containerHeight, scrollHeight));

            // 如果内容高度较小，直接单次截图
            if (scrollHeight <= 3000) {
                return captureSingleContainerScreenshot(page, uploadUrl, containerInfo, originalViewport);
            } else {
                // 内容很长，使用分段截图然后拼接
                return captureContainerWithSegments(page, uploadUrl, containerInfo, originalViewport);
            }

        } catch (Exception e) {
            System.err.println("截取最后一个回复容器失败: " + e.getMessage());
            e.printStackTrace();
            return captureFullPageScreenshot(page, uploadUrl);
        } finally {
            // 恢复原始视口大小
            if (originalViewport != null) {
                page.setViewportSize(originalViewport.width, originalViewport.height);
            }
            // 恢复被隐藏的元素
            restoreFixedElements(page);
            // 清理临时文件
            if (finalScreenshotPath != null) {
                try {
                    Files.deleteIfExists(finalScreenshotPath);
                } catch (IOException e) {
                    System.err.println("清理临时文件失败: " + e.getMessage());
                }
            }
        }
    }

    /**
     * 单次截图捕获整个容器（适用于较短的回复）
     */
    private String captureSingleContainerScreenshot(Page page, String uploadUrl, Map<String, Object> containerInfo, ViewportSize originalViewport) {
        try {
            double containerX = getDoubleValue(containerInfo, "x");
            double containerY = getDoubleValue(containerInfo, "y");
            double containerWidth = getDoubleValue(containerInfo, "width");
            double containerHeight = getDoubleValue(containerInfo, "height");

            // 调整视口大小以适应容器
            int viewportWidth = Math.max(originalViewport.width, (int) Math.ceil(containerWidth) + 100);
            int viewportHeight = Math.max(originalViewport.height, (int) Math.ceil(containerHeight) + 100);
            
            page.setViewportSize(viewportWidth, viewportHeight);
            page.waitForTimeout(500);

            // 重新滚动到容器位置
            page.evaluate("""
                () => {
                    const containers = document.querySelectorAll('div._4f9bf79.d7dc56a8._43c05b5');
                    if (containers.length > 0) {
                        const lastContainer = containers[containers.length - 1];
                        lastContainer.scrollIntoView({ behavior: 'auto', block: 'start' });
                    }
                }
            """);
            
            page.waitForTimeout(800);

            // 获取页面边界，确保截图区域不超出页面
            Map<String, Object> pageSize = (Map<String, Object>) page.evaluate(
                "() => ({ width: document.documentElement.scrollWidth, height: document.documentElement.scrollHeight })"
            );
            double pageWidth = getDoubleValue(pageSize, "width");
            double pageHeight = getDoubleValue(pageSize, "height");

            // 调整截图区域
            double clipX = Math.max(0, containerX);
            double clipY = Math.max(0, containerY);
            double clipWidth = Math.min(containerWidth, pageWidth - clipX);
            double clipHeight = Math.min(containerHeight, pageHeight - clipY);

            // 创建截图路径
            Path screenshotPath = Paths.get(System.getProperty("java.io.tmpdir"),
                    "deepseek_last_reply_" + UUID.randomUUID() + ".png");

            // 截图
            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(screenshotPath)
                    .setClip(clipX, clipY, clipWidth, clipHeight));

            // 上传并获取URL
            String result = uploadFile(uploadUrl, screenshotPath.toString());
            JSONObject jsonObject = JSONObject.parseObject(result);
            String shareImgUrl = jsonObject.getString("url");

            // 清理临时文件
            Files.deleteIfExists(screenshotPath);

            System.out.println("单次截图完成: " + shareImgUrl);
            return shareImgUrl;

        } catch (Exception e) {
            System.err.println("单次截图失败: " + e.getMessage());
            return captureFullPageScreenshot(page, uploadUrl);
        }
    }

    /**
     * 分段截图然后拼接（适用于很长的回复）
     */
    private String captureContainerWithSegments(Page page, String uploadUrl, Map<String, Object> containerInfo, ViewportSize originalViewport) {
        List<Path> segmentPaths = new ArrayList<>();
        Path finalPath = null;

        try {
            double containerHeight = getDoubleValue(containerInfo, "scrollHeight");
            double containerWidth = getDoubleValue(containerInfo, "width");
            
            // 每段的高度（避免过大的截图）
            int segmentHeight = 2000;
            int totalSegments = (int) Math.ceil(containerHeight / segmentHeight);
            
            System.out.println(String.format("容器总高度: %.0f, 分为 %d 段截图", containerHeight, totalSegments));

            // 调整视口以适应宽度
            page.setViewportSize(
                Math.max(originalViewport.width, (int) containerWidth + 100),
                Math.max(originalViewport.height, segmentHeight + 100)
            );
            page.waitForTimeout(500);

            // 分段截图
            for (int i = 0; i < totalSegments; i++) {
                // 计算当前段的滚动位置
                int scrollOffset = i * segmentHeight;
                
                // 滚动到指定位置
                page.evaluate(String.format("""
                    (scrollOffset) => {
                        const containers = document.querySelectorAll('div._4f9bf79.d7dc56a8._43c05b5');
                        if (containers.length > 0) {
                            const lastContainer = containers[containers.length - 1];
                            lastContainer.scrollTop = scrollOffset;
                            
                            // 同时滚动页面确保容器可见
                            const rect = lastContainer.getBoundingClientRect();
                            if (rect.top < 0 || rect.bottom > window.innerHeight) {
                                lastContainer.scrollIntoView({ behavior: 'auto', block: 'center' });
                            }
                        }
                    }
                """, scrollOffset));
                
                page.waitForTimeout(300);

                // 获取当前段的截图区域
                Map<String, Object> segmentInfo = (Map<String, Object>) page.evaluate(String.format("""
                    (segmentIndex, segmentHeight) => {
                        const containers = document.querySelectorAll('div._4f9bf79.d7dc56a8._43c05b5');
                        if (containers.length === 0) return null;
                        
                        const lastContainer = containers[containers.length - 1];
                        const rect = lastContainer.getBoundingClientRect();
                        
                        // 安全检查所有数值，避免 NaN
                        const safeValue = (val, defaultVal = 0) => {
                            return (isNaN(val) || !isFinite(val)) ? defaultVal : val;
                        };
                        
                        // 计算当前段的实际高度
                        const scrollHeight = safeValue(lastContainer.scrollHeight, 1000);
                        const remainingHeight = scrollHeight - (segmentIndex * segmentHeight);
                        const actualSegmentHeight = Math.max(100, Math.min(segmentHeight, remainingHeight));
                        
                        return {
                            x: safeValue(Math.max(0, rect.x - 20)),
                            y: safeValue(Math.max(0, rect.y)),
                            width: safeValue(rect.width + 40, 800),
                            height: safeValue(Math.min(actualSegmentHeight + 70, rect.height), 600),
                            scrollTop: safeValue(lastContainer.scrollTop)
                        };
                    }
                """, i, segmentHeight));

                if (segmentInfo == null) continue;

                double segX = getDoubleValue(segmentInfo, "x");
                double segY = getDoubleValue(segmentInfo, "y");
                double segWidth = getDoubleValue(segmentInfo, "width");
                double segHeight = getDoubleValue(segmentInfo, "height");

                // 验证截图参数的有效性
                if (segWidth <= 0 || segHeight <= 0) {
                    System.err.println(String.format("跳过无效的截图参数: x=%f, y=%f, width=%f, height=%f", 
                            segX, segY, segWidth, segHeight));
                    continue;
                }

                // 创建段截图路径
                Path segmentPath = Paths.get(System.getProperty("java.io.tmpdir"),
                        "segment_" + i + "_" + UUID.randomUUID() + ".png");

                try {
                    // 截图当前段
                    page.screenshot(new Page.ScreenshotOptions()
                            .setPath(segmentPath)
                            .setClip(segX, segY, segWidth, segHeight));
                    
                    segmentPaths.add(segmentPath);
                    System.out.println(String.format("完成第 %d/%d 段截图", i + 1, totalSegments));
                } catch (Exception e) {
                    System.err.println(String.format("第 %d 段截图失败: %s", i + 1, e.getMessage()));
                    // 继续下一段截图，不中断整个流程
                }
            }

            // 拼接所有段
            if (!segmentPaths.isEmpty()) {
                try {
                    finalPath = Paths.get(System.getProperty("java.io.tmpdir"),
                            "deepseek_combined_" + UUID.randomUUID() + ".png");
                    
                    combineImagesVertically(segmentPaths, finalPath);

                    // 上传拼接后的图片
                    String result = uploadFile(uploadUrl, finalPath.toString());
                    JSONObject jsonObject = JSONObject.parseObject(result);
                    String shareImgUrl = jsonObject.getString("url");

                    System.out.println("分段截图拼接完成: " + shareImgUrl);
                    return shareImgUrl;
                } catch (Exception e) {
                    System.err.println("分段截图拼接失败: " + e.getMessage());
                    // 继续执行，最后会回退到全屏截图
                }
            } else {
                System.err.println("所有分段截图都失败，回退到全屏截图");
            }

        } catch (Exception e) {
            System.err.println("分段截图失败: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // 清理所有临时文件
            cleanupTempFiles(segmentPaths, finalPath);
        }

        return captureFullPageScreenshot(page, uploadUrl);
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
        double result = 0.0;
        
        if (value instanceof Integer) {
            result = ((Integer) value).doubleValue();
        } else if (value instanceof Double) {
            result = (Double) value;
        } else if (value instanceof Number) {
            result = ((Number) value).doubleValue();
        } else {
            throw new IllegalArgumentException("无法将值转换为 double: " + value);
        }
        
        // 检查并处理 NaN 和无穷大值
        if (Double.isNaN(result) || Double.isInfinite(result)) {
            System.err.println("警告: 检测到无效数值 " + key + "=" + result + "，使用默认值 0.0");
            return 0.0;
        }
        
        return result;
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