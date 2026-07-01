/**
 * PeaceHaven 登录模块
 * 提供登录弹窗、用户状态检测、退出登录等功能
 */
(function() {
    // === 状态 ===
    let currentUser = null;

    // === 初始化 ===
    document.addEventListener('DOMContentLoaded', function() {
        checkLoginStatus();
    });

    function checkLoginStatus(callback) {
        fetch('/api/auth/me')
            .then(r => r.json())
            .then(data => {
                currentUser = data.loggedIn ? data : null;
                updateNavbar();
                if (callback) callback();
            })
            .catch(() => { currentUser = null; });
    }

    // === 更新导航栏用户区域 ===
    function updateNavbar() {
        const navActions = document.querySelector('.nav-actions');
        if (!navActions) return;

        // 移除旧的用户区域
        const oldUserArea = document.getElementById('userArea');
        if (oldUserArea) oldUserArea.remove();

        const userArea = document.createElement('div');
        userArea.id = 'userArea';

        if (currentUser) {
            userArea.className = 'user-logged';
            userArea.innerHTML =
                '<div class="user-nickname-btn" id="userNicknameBtn">' +
                    '<span class="user-nickname-text">' + (currentUser.nickname || '用户') + '</span>' +
                    '<span class="user-dropdown-arrow" id="dropdownArrow">&#9662;</span>' +
                '</div>' +
                '<div class="user-dropdown" id="userDropdown">' +
                    '<div class="dropdown-header">' +
                        '<div class="dropdown-nickname">' + currentUser.nickname + '</div>' +
                        '<div class="dropdown-role">' + (currentUser.role === 'ADMIN' ? '管理员' : '用户') + '</div>' +
                    '</div>' +
                    '<button class="dropdown-item" id="editNicknameBtn">修改昵称</button>' +
                    (currentUser.role === 'ADMIN' ? '<a href="/admin/activities" class="dropdown-item">后台管理</a>' : '') +
                    '<button class="dropdown-item dropdown-logout" id="logoutBtn">退出登录</button>' +
                '</div>';

            navActions.appendChild(userArea);

            // 下拉菜单切换
            var nicknameBtn = document.getElementById('userNicknameBtn');
            var dropdown = document.getElementById('userDropdown');
            var arrow = document.getElementById('dropdownArrow');

            nicknameBtn.addEventListener('click', function(e) {
                e.stopPropagation();
                var isOpen = dropdown.classList.toggle('show');
                arrow.classList.toggle('open', isOpen);
            });

            // 点击其他地方关闭下拉
            document.addEventListener('click', function() {
                dropdown.classList.remove('show');
                arrow.classList.remove('open');
            });

            // 修改昵称
            document.getElementById('editNicknameBtn').addEventListener('click', function() {
                dropdown.classList.remove('show');
                arrow.classList.remove('open');
                openNicknameModal(true);
            });

            // 退出
            document.getElementById('logoutBtn').addEventListener('click', function() {
                fetch('/api/auth/logout', { method: 'POST' })
                    .then(() => {
                        currentUser = null;
                        updateNavbar();
                        // 退出登录事件
                        window.dispatchEvent(new CustomEvent('auth:logout'));
                    });
            });
        } else {
            userArea.className = 'user-not-logged';
            userArea.innerHTML = '<button class="btn-login" id="loginBtn">登录</button>';
            navActions.appendChild(userArea);

            document.getElementById('loginBtn').addEventListener('click', openLoginModal);
        }
    }

    // === 登录弹窗 ===
    function openLoginModal() {
        // 防止重复创建
        if (document.getElementById('loginModal')) {
            document.getElementById('loginModal').classList.add('show');
            return;
        }

        var overlay = document.createElement('div');
        overlay.id = 'loginModal';
        overlay.className = 'login-modal-overlay show';
        overlay.innerHTML =
            '<div class="login-modal">' +
                '<button class="login-close" id="loginClose">&times;</button>' +
                '<div class="login-modal-header">' +
                    '<h2>欢迎来到长安</h2>' +
                    '<p>手机验证码登录，未注册将自动创建账号</p>' +
                '</div>' +
                '<div class="login-modal-body">' +
                    '<div class="login-field">' +
                        '<label for="loginPhone">手机号</label>' +
                        '<input type="tel" id="loginPhone" maxlength="11" placeholder="请输入手机号" autocomplete="tel">' +
                    '</div>' +
                    '<div class="login-field login-code-field">' +
                        '<label for="loginCode">验证码</label>' +
                        '<div class="code-input-wrapper">' +
                            '<input type="text" id="loginCode" maxlength="4" placeholder="4位验证码" inputmode="numeric" autocomplete="one-time-code">' +
                            '<button class="btn-send-code" id="sendCodeBtn">获取验证码</button>' +
                        '</div>' +
                    '</div>' +
                    '<div class="login-agreement">' +
                        '<label class="agreement-label">' +
                            '<input type="checkbox" id="loginAgreed">' +
                            '<span>我已阅读并同意</span>' +
                        '</label>' +
                        '<a href="/agreement" target="_blank" class="agreement-link">《用户服务协议》</a>' +
                    '</div>' +
                    '<button class="btn-login-submit" id="loginSubmitBtn">登录</button>' +
                    '<div class="login-message" id="loginMessage"></div>' +
                '</div>' +
            '</div>';

        document.body.appendChild(overlay);

        // 关闭按钮
        document.getElementById('loginClose').addEventListener('click', closeLoginModal);
        overlay.addEventListener('click', function(e) {
            if (e.target === overlay) closeLoginModal();
        });

        // 发送验证码
        document.getElementById('sendCodeBtn').addEventListener('click', handleSendCode);

        // 登录提交
        document.getElementById('loginSubmitBtn').addEventListener('click', handleLogin);

        // Enter 键提交
        document.getElementById('loginCode').addEventListener('keydown', function(e) {
            if (e.key === 'Enter') handleLogin();
        });

        // 手机号输入限制
        document.getElementById('loginPhone').addEventListener('input', function() {
            this.value = this.value.replace(/\D/g, '');
        });

        // 验证码输入限制
        document.getElementById('loginCode').addEventListener('input', function() {
            this.value = this.value.replace(/\D/g, '');
        });
    }

    function closeLoginModal() {
        var modal = document.getElementById('loginModal');
        if (modal) {
            modal.classList.remove('show');
            setTimeout(function() { modal.remove(); }, 300);
        }
    }

    // === 发送验证码 ===
    function handleSendCode() {
        var phone = document.getElementById('loginPhone').value.trim();
        var btn = document.getElementById('sendCodeBtn');

        if (!/^1[3-9]\d{9}$/.test(phone)) {
            showLoginMessage('请输入正确的手机号', false);
            return;
        }

        if (btn.disabled) return;

        btn.disabled = true;
        btn.textContent = '发送中...';

        fetch('/api/auth/send-code', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ phone: phone })
        })
        .then(r => r.json())
        .then(data => {
            if (data.success) {
                showLoginMessage('验证码已发送', true);
                startCountdown(btn, 60);
            } else {
                showLoginMessage(data.message, false);
                btn.disabled = false;
                btn.textContent = '获取验证码';
            }
        })
        .catch(() => {
            showLoginMessage('网络错误，请重试', false);
            btn.disabled = false;
            btn.textContent = '获取验证码';
        });
    }

    function startCountdown(btn, seconds) {
        var remaining = seconds;
        btn.textContent = remaining + 's';
        var timer = setInterval(function() {
            remaining--;
            if (remaining <= 0) {
                clearInterval(timer);
                btn.disabled = false;
                btn.textContent = '重新获取';
            } else {
                btn.textContent = remaining + 's';
            }
        }, 1000);
    }

    // === 登录 ===
    function handleLogin() {
        var phone = document.getElementById('loginPhone').value.trim();
        var code = document.getElementById('loginCode').value.trim();
        var agreed = document.getElementById('loginAgreed').checked;

        if (!/^1[3-9]\d{9}$/.test(phone)) {
            showLoginMessage('请输入正确的手机号', false);
            return;
        }

        if (code.length !== 4) {
            showLoginMessage('请输入4位验证码', false);
            return;
        }

        if (!agreed) {
            showLoginMessage('请先阅读并同意用户协议', false);
            return;
        }

        var btn = document.getElementById('loginSubmitBtn');
        btn.disabled = true;
        btn.textContent = '登录中...';

        fetch('/api/auth/login', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ phone: phone, code: code, agreed: 'true' })
        })
        .then(r => r.json())
        .then(data => {
            if (data.success) {
                showLoginMessage('登录成功！', true);
                var isNewUser = data.isNewUser;
                setTimeout(function() {
                    closeLoginModal();
                    checkLoginStatus(function() {
                        // 登录成功事件，供其他页面监听刷新数据
                        window.dispatchEvent(new CustomEvent('auth:login'));
                        // 新用户首次登录弹昵称设置框
                        if (isNewUser) {
                            openNicknameModal();
                        }
                    });
                }, 600);
            } else {
                showLoginMessage(data.message, false);
                btn.disabled = false;
                btn.textContent = '登录';
            }
        })
        .catch(() => {
            showLoginMessage('网络错误，请重试', false);
            btn.disabled = false;
            btn.textContent = '登录';
        });
    }

    function showLoginMessage(msg, success) {
        var el = document.getElementById('loginMessage');
        if (!el) return;
        el.textContent = msg;
        el.className = 'login-message ' + (success ? 'msg-success' : 'msg-error');
    }

    // === 昵称设置弹窗 ===
    function openNicknameModal(isEdit) {
        if (document.getElementById('nicknameModal')) return;

        var overlay = document.createElement('div');
        overlay.id = 'nicknameModal';
        overlay.className = 'nickname-modal-overlay show';
        overlay.innerHTML =
            '<div class="nickname-modal">' +
                '<h2>' + (isEdit ? '修改昵称' : '游戏昵称') + '</h2>' +
                '<p>' + (isEdit ? '给自己换个名字吧' : '给自己取个名字吧') + '</p>' +
                '<input type="text" id="nicknameInput" maxlength="14" placeholder="输入游戏昵称" value="' + (isEdit ? (currentUser.nickname || '') : '') + '" autofocus>' +
                '<button class="btn-nickname-submit" id="nicknameSubmitBtn">' + (isEdit ? '保存修改' : '确认设置') + '</button>' +
                '<div class="nickname-message" id="nicknameMessage"></div>' +
            '</div>';

        document.body.appendChild(overlay);

        // 点击背景关闭（仅编辑模式）
        if (isEdit) {
            overlay.addEventListener('click', function(e) {
                if (e.target === overlay) {
                    overlay.classList.remove('show');
                    setTimeout(function() { overlay.remove(); }, 300);
                }
            });
        }

        var input = document.getElementById('nicknameInput');
        var submitBtn = document.getElementById('nicknameSubmitBtn');

        function submitNickname() {
            var nickname = input.value.trim();
            if (!nickname) {
                showNicknameMessage('请输入昵称', false);
                return;
            }
            if (nickname.length > 14) {
                showNicknameMessage('昵称不能超过14个字符', false);
                return;
            }

            submitBtn.disabled = true;
            submitBtn.textContent = '设置中...';

            fetch('/api/auth/nickname', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ nickname: nickname })
            })
            .then(r => r.json())
            .then(data => {
                if (data.success) {
                    showNicknameMessage('设置成功！', true);
                    currentUser.nickname = data.nickname;
                    setTimeout(function() {
                        overlay.classList.remove('show');
                        setTimeout(function() { overlay.remove(); }, 300);
                        updateNavbar();
                    }, 600);
                } else {
                    showNicknameMessage(data.message, false);
                    submitBtn.disabled = false;
                    submitBtn.textContent = '确认设置';
                }
            })
            .catch(() => {
                showNicknameMessage('网络错误，请重试', false);
                submitBtn.disabled = false;
                submitBtn.textContent = '确认设置';
            });
        }

        submitBtn.addEventListener('click', submitNickname);
        input.addEventListener('keydown', function(e) {
            if (e.key === 'Enter') submitNickname();
        });
    }

    function showNicknameMessage(msg, success) {
        var el = document.getElementById('nicknameMessage');
        if (!el) return;
        el.textContent = msg;
        el.className = 'nickname-message ' + (success ? 'msg-success' : 'msg-error');
    }
})();
