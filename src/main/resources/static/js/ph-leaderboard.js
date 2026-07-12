/**
 * PeaceHaven 通用排行榜组件
 *
 * 使用方式：
 * <link rel="stylesheet" th:href="@{/css/ph-leaderboard.css}">
 * <script th:src="@{/js/ph-leaderboard.js}"></script>
 *
 * PHLeaderboard.create({
 *   container: '#leaderboard',            // 容器选择器或 DOM 元素
 *   slug: 'battle-showdown-1',            // 活动 slug
 *   columns: [
 *     { field: 'rank',     label: '排名', type: 'rank' },
 *     { field: 'nickname', label: '选手' },
 *     { field: 'wins',     label: '胜',   type: 'number' },
 *     { field: 'losses',   label: '负',   type: 'number' },
 *     { field: 'points',   label: '积分', type: 'number', color: '#E8833A' }
 *   ],
 *   sort: [
 *     { field: 'points', order: 'desc' },
 *     { field: 'wins',   order: 'desc' }
 *   ],
 *   emptyText: '暂无数据',                // 空状态文案（可选）
 *   errorText: '加载失败，请刷新'          // 错误状态文案（可选）
 * });
 *
 * 列类型 (type):
 *   - 默认: 纯文本
 *   - 'rank': 排名列，自动分配序号，前3名显示奖牌 emoji
 *   - 'number': 数字列
 *
 * 列颜色 (color):
 *   - 内置默认: wins=绿色, losses=红色, points=主题色
 *   - 可通过 color 属性覆盖，如 { field: 'points', color: '#FF6600' }
 *
 * 排序规则 (sort):
 *   - field: 排序字段名（对应 API 返回的 key）
 *   - order: 'asc' 或 'desc'
 *   - 多字段排序时按数组顺序优先级
 *
 * API: GET /api/pvp/{slug}/rankings
 *   返回: { rankings: [{ nickname, campName, avatar, wins, losses, points, rankNum, completion }, ...] }
 */
var PHLeaderboard = (function () {
    'use strict';

    /** 内置字段默认颜色 */
    var DEFAULT_COLORS = {
        wins: '#4CAF50',
        losses: '#E8654A',
        points: 'var(--accent-primary, #E8833A)'
    };

    /**
     * 创建排行榜
     */
    function create(config) {
        if (!config || !config.container || !config.slug || !config.columns) {
            console.error('[PHLeaderboard] 缺少必要参数: container, slug, columns');
            return;
        }

        var container = typeof config.container === 'string'
            ? document.querySelector(config.container)
            : config.container;

        if (!container) {
            console.error('[PHLeaderboard] 容器未找到:', config.container);
            return;
        }

        var columns = config.columns;
        var sortRules = config.sort || [];
        var emptyText = config.emptyText || '暂无数据';
        var errorText = config.errorText || '加载失败，请刷新';
        var slug = config.slug;

        // 构建 grid-template-columns
        var colWidths = columns.map(function (col) {
            if (col.type === 'rank') return '50px';
            if (col.type === 'number') return '80px';
            return '1fr';
        });
        var gridCols = colWidths.join(' ');

        // 渲染骨架
        container.innerHTML =
            '<div class="ph-lb-wrap" data-col-count="' + columns.length + '" style="--ph-lb-cols: ' + gridCols + '">' +
                '<div class="ph-lb-header">' +
                    columns.map(function (col) {
                        return '<span class="ph-lb-col">' + col.label + '</span>';
                    }).join('') +
                '</div>' +
                '<div class="ph-lb-body">' +
                    '<div class="ph-lb-empty"><p>📡 加载中...</p></div>' +
                '</div>' +
            '</div>';

        var bodyEl = container.querySelector('.ph-lb-body');

        // 获取数据
        fetch('/api/pvp/' + slug + '/rankings')
            .then(function (r) { return r.json(); })
            .then(function (data) {
                var list = data.rankings || [];
                if (!list.length) {
                    bodyEl.innerHTML = '<div class="ph-lb-empty"><p>' + emptyText + '</p></div>';
                    return;
                }

                // 排序
                list = sortData(list, sortRules);

                // 分配 rank
                list.forEach(function (item, i) {
                    item.rank = i + 1;
                });

                // 渲染
                var html = list.map(function (item) {
                    return renderRow(item, columns);
                }).join('');
                bodyEl.innerHTML = html;
            })
            .catch(function () {
                bodyEl.innerHTML = '<div class="ph-lb-empty"><p>⚠️ ' + errorText + '</p></div>';
            });
    }

    /**
     * 多字段排序
     */
    function sortData(list, rules) {
        if (!rules || !rules.length) return list;

        return list.slice().sort(function (a, b) {
            for (var i = 0; i < rules.length; i++) {
                var field = rules[i].field;
                var desc = rules[i].order === 'desc';
                var va = a[field] || 0;
                var vb = b[field] || 0;

                if (va !== vb) {
                    return desc ? (vb - va) : (va - vb);
                }
            }
            return 0;
        });
    }

    /**
     * 渲染单行
     */
    function renderRow(item, columns) {
        var cells = columns.map(function (col) {
            var value = item[col.field];
            var cls = 'ph-lb-cell';

            if (col.type === 'rank') {
                var rankClass = value <= 3 ? ' rank-' + value : '';
                var medal = value === 1 ? '🥇' : value === 2 ? '🥈' : value === 3 ? '🥉' : value;
                return '<span class="' + cls + ' ph-lb-rank' + rankClass + '">' + medal + '</span>';
            }

            // 字段专属 class (ph-lb-wins, ph-lb-losses, ph-lb-points 等)
            var fieldClass = DEFAULT_COLORS[col.field] ? ' ph-lb-' + col.field : '';

            if (col.type === 'number') {
                // 只有显式指定 col.color 时才用 inline style，否则走 CSS class
                var numStyle = col.color ? ' style="color:' + col.color + '"' : '';
                return '<span class="' + cls + ' ph-lb-number' + fieldClass + '"' + numStyle + '>' + (value != null ? value : '-') + '</span>';
            }

            // 默认文本列 (nickname 等)
            var nameClass = col.field === 'nickname' ? ' ph-lb-name' : '';
            var textStyle = col.color ? ' style="color:' + col.color + '"' : '';
            return '<span class="' + cls + nameClass + fieldClass + '"' + textStyle + '>' + (value || '-') + '</span>';
        });

        return '<div class="ph-lb-row">' + cells.join('') + '</div>';
    }

    return {
        create: create
    };
})();
