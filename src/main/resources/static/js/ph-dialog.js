/**
 * PeaceHaven 通用 Dialog 组件
 *
 * 使用方式：
 * <link rel="stylesheet" th:href="@{/css/ph-dialog.css}">
 * <script th:src="@{/js/ph-dialog.js}"></script>
 *
 * API：
 * PHDialog.show({ title, message, icon?, confirmText, confirmStyle?, onConfirm })
 *   - 单按钮对话框
 *
 * PHDialog.confirm({ title, message, icon?, confirmText, cancelText, confirmStyle?, onConfirm, onCancel? })
 *   - 双按钮确认框
 *
 * confirmStyle: 'primary'（默认）| 'danger'
 */
var PHDialog = (function () {
    'use strict';

    var activeOverlay = null;

    function createOverlay() {
        var overlay = document.createElement('div');
        overlay.className = 'ph-dialog-overlay';
        document.body.appendChild(overlay);
        // 强制回流以触发过渡动画
        overlay.offsetHeight;
        return overlay;
    }

    function close(overlay) {
        if (!overlay) return;
        overlay.classList.remove('ph-visible');
        setTimeout(function () {
            if (overlay.parentNode) overlay.parentNode.removeChild(overlay);
        }, 250);
        if (activeOverlay === overlay) activeOverlay = null;
    }

    /**
     * 单按钮对话框
     */
    function show(opts) {
        // 关闭已有弹窗
        if (activeOverlay) close(activeOverlay);

        var overlay = createOverlay();
        activeOverlay = overlay;

        var iconHtml = opts.icon ? '<div class="ph-dialog-icon">' + opts.icon + '</div>' : '';
        var btnClass = opts.confirmStyle === 'danger' ? 'ph-dialog-btn-danger' : 'ph-dialog-btn-primary';

        overlay.innerHTML =
            '<div class="ph-dialog">' +
                iconHtml +
                '<div class="ph-dialog-title">' + (opts.title || '') + '</div>' +
                '<div class="ph-dialog-message">' + (opts.message || '') + '</div>' +
                '<div class="ph-dialog-actions">' +
                    '<button class="ph-dialog-btn ' + btnClass + '">' + (opts.confirmText || '确定') + '</button>' +
                '</div>' +
            '</div>';

        var btn = overlay.querySelector('.ph-dialog-btn');
        btn.addEventListener('click', function () {
            close(overlay);
            if (typeof opts.onConfirm === 'function') opts.onConfirm();
        });

        // 点击遮罩关闭
        overlay.addEventListener('click', function (e) {
            if (e.target === overlay) close(overlay);
        });

        // 显示动画
        requestAnimationFrame(function () {
            overlay.classList.add('ph-visible');
        });
    }

    /**
     * 双按钮确认框
     */
    function confirm(opts) {
        if (activeOverlay) close(activeOverlay);

        var overlay = createOverlay();
        activeOverlay = overlay;

        var iconHtml = opts.icon ? '<div class="ph-dialog-icon">' + opts.icon + '</div>' : '';
        var btnClass = opts.confirmStyle === 'danger' ? 'ph-dialog-btn-danger' : 'ph-dialog-btn-primary';

        overlay.innerHTML =
            '<div class="ph-dialog">' +
                iconHtml +
                '<div class="ph-dialog-title">' + (opts.title || '') + '</div>' +
                '<div class="ph-dialog-message">' + (opts.message || '') + '</div>' +
                '<div class="ph-dialog-actions">' +
                    '<button class="ph-dialog-btn ph-dialog-btn-secondary">' + (opts.cancelText || '取消') + '</button>' +
                    '<button class="ph-dialog-btn ' + btnClass + '">' + (opts.confirmText || '确定') + '</button>' +
                '</div>' +
            '</div>';

        var buttons = overlay.querySelectorAll('.ph-dialog-btn');
        var cancelBtn = buttons[0];
        var confirmBtn = buttons[1];

        cancelBtn.addEventListener('click', function () {
            close(overlay);
            if (typeof opts.onCancel === 'function') opts.onCancel();
        });

        confirmBtn.addEventListener('click', function () {
            close(overlay);
            if (typeof opts.onConfirm === 'function') opts.onConfirm();
        });

        overlay.addEventListener('click', function (e) {
            if (e.target === overlay) {
                close(overlay);
                if (typeof opts.onCancel === 'function') opts.onCancel();
            }
        });

        requestAnimationFrame(function () {
            overlay.classList.add('ph-visible');
        });
    }

    return { show: show, confirm: confirm };
})();
