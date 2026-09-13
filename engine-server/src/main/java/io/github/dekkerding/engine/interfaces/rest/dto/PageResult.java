package io.github.dekkerding.engine.interfaces.rest.dto;

import java.util.List;

/**
 * 分页结果 DTO —— 列表接口的统一翻页信封。
 *
 * <p>【教学注释 · 为什么 total 不可少】前端分页组件要算总页数：
 * {@code pages = ceil(total / size)}。没有 total 就只能"下一页点了没数据才算到底"——
 * 这是接口设计里最经典的"消费者驱动字段"案例。
 */
public class PageResult<T> {

    private final List<T> items;
    private final long total;
    private final int page;   // 从 0 开始（与 Spring Data 对齐）
    private final int size;

    public PageResult(List<T> items, long total, int page, int size) {
        this.items = items;
        this.total = total;
        this.page = page;
        this.size = size;
    }

    /** 内存分页（数据源已全量取回，切一刀即可——学习规模最诚实的分页） */
    public static <T> PageResult<T> of(List<T> all, int page, int size) {
        int from = Math.min(page * size, all.size());
        int to = Math.min(from + size, all.size());
        return new PageResult<>(all.subList(from, to), all.size(), page, size);
    }

    public List<T> getItems() { return items; }
    public long getTotal() { return total; }
    public int getPage() { return page; }
    public int getSize() { return size; }
}
