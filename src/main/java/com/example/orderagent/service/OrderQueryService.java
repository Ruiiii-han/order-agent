package com.example.orderagent.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 订单链路五表的只读查询。
 *
 * <p>用 JdbcTemplate 而不是 JPA：读多写少、SQL 简单、返回结构要拼成 Map 供工具摘要使用，
 * JdbcTemplate 更直接（也算一次技术选型答题点）。
 */
@Repository
@RequiredArgsConstructor
public class OrderQueryService {

    private final JdbcTemplate jdbc;

    public Optional<Map<String, Object>> findOrder(String orderNo) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM order_main WHERE order_no = ?", orderNo);
        return rows.isEmpty() ? Optional.empty() : Optional.of(new LinkedHashMap<>(rows.get(0)));
    }

    public List<Map<String, Object>> findPayments(String orderNo) {
        return jdbc.queryForList("SELECT * FROM pay_record WHERE order_no = ? ORDER BY id", orderNo)
                .stream().map(m -> (Map<String, Object>) new LinkedHashMap<>(m)).toList();
    }

    public List<Map<String, Object>> findStock(long skuId) {
        return jdbc.queryForList("SELECT * FROM stock_record WHERE sku_id = ?", skuId)
                .stream().map(m -> (Map<String, Object>) new LinkedHashMap<>(m)).toList();
    }

    public List<Map<String, Object>> findLogistics(String orderNo) {
        return jdbc.queryForList("SELECT * FROM logistics_record WHERE order_no = ?", orderNo)
                .stream().map(m -> (Map<String, Object>) new LinkedHashMap<>(m)).toList();
    }

    public List<Map<String, Object>> findRisk(String orderNo) {
        return jdbc.queryForList("SELECT * FROM risk_record WHERE order_no = ?", orderNo)
                .stream().map(m -> (Map<String, Object>) new LinkedHashMap<>(m)).toList();
    }
}