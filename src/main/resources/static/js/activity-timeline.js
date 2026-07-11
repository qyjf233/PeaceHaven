/**
 * PeaceHaven 活动进程时间轴 - 公共渲染组件
 *
 * 使用方式（在活动页模板中添加以下3行即可）：
 *
 * 1. <head> 中引入样式：
 *    <link rel="stylesheet" th:href="@{/css/activity-timeline.css}">
 *
 * 2. HTML 中添加容器（configJson 由 ActivityController 自动注入）：
 *    <div class="ph-timeline" id="phTimeline" th:attr="data-config=${configJson}"></div>
 *
 * 3. </body> 前引入脚本：
 *    <script th:src="@{/js/activity-timeline.js}"></script>
 *
 * 数据格式（activity_config.config_json 中的 timeline 字段）：
 * {
 *   "timeline": [
 *     {"label":"报名期","icon":"📋","phase":"register","start":"2026-07-01T00:00","end":"2026-07-05T23:59"},
 *     {"label":"瑞士轮","icon":"🔄","phase":"swiss","start":"2026-07-06T00:00","end":"2026-07-12T23:59"}
 *   ]
 * }
 *
 * 渲染完成后：
 * - window.__activePhase 将持有当前活跃阶段的 phase 标识
 * - 其他模块可通过 window.__activePhase 判断当前阶段（如开放报名/投票等功能）
 */
(function () {
    'use strict';

    var container = document.getElementById('phTimeline');
    if (!container) return;

    var configStr = container.getAttribute('data-config');
    if (!configStr) return;

    var config = {};
    try { config = JSON.parse(configStr); } catch (e) { return; }

    var timelineStr = config.timeline;
    if (!timelineStr) return;

    var steps = [];
    try {
        steps = typeof timelineStr === 'string' ? JSON.parse(timelineStr) : timelineStr;
    } catch (e) { return; }
    if (!steps || !steps.length) return;

    var now = new Date();
    var activePhase = null;

    function fmt(d) {
        return d ? (d.getMonth() + 1) + '/' + d.getDate() : '';
    }

    steps.forEach(function (step) {
        var start = step.start ? new Date(step.start) : null;
        var end = step.end ? new Date(step.end) : null;

        var stateClass = '';
        if (end && now > end) {
            stateClass = 'done';
        } else if (start && now >= start && end && now <= end) {
            stateClass = 'active';
            if (step.phase) activePhase = step.phase;
        }

        var dateStr = '';
        if (start && end) {
            dateStr = start.toDateString() === end.toDateString()
                ? fmt(start)
                : fmt(start) + ' ~ ' + fmt(end);
        } else if (start) {
            dateStr = fmt(start);
        }

        var div = document.createElement('div');
        div.className = 'ph-tl-step' + (stateClass ? ' ' + stateClass : '');
        div.innerHTML = '<div class="ph-tl-dot">' + (step.icon || '') + '</div>'
            + '<div class="ph-tl-label">' + (step.label || '') + '</div>'
            + '<div class="ph-tl-date">' + dateStr + '</div>';
        container.appendChild(div);
    });

    // 暴露当前活跃阶段到全局，供页面其他模块使用
    window.__activePhase = activePhase;
    console.log('[Timeline] 当前活跃阶段:', activePhase || '无');
})();
