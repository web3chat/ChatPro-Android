(function() {
    const _callbacks = new Map();

    const provider = {
        isMetaMask: true,
        selectedAddress: null,
        chainId: null,          // 建议初始化时由 Kotlin 注入真实值
        networkVersion: null,   // 建议初始化时由 Kotlin 注入真实值

        // --- 新增：事件存储与订阅逻辑 ---
        _events: {},
        on: function(event, listener) {
            if (!this._events[event]) this._events[event] = [];
            this._events[event].push(listener);
            console.log(`[Provider] Listener added for: ${event}`);
        },
        removeListener: function(event, listener) {
            if (!this._events[event]) return;
            this._events[event] = this._events[event].filter(l => l !== listener);
        },
        // 供 Kotlin 调用的触发器
        emit: function(event, data) {
            console.log(`[Provider] Emitting event: ${event} with data:`, data);
            // 自动同步内部属性，保证 DApp 读取到最新状态
            if (event === 'chainChanged') this.chainId = data;
            if (event === 'networkChanged') this.networkVersion = data;
            if (event === 'accountsChanged') this.selectedAddress = data[0];

            const listeners = this._events[event] || [];
            listeners.forEach(fn => {
                try { fn(data); } catch (e) { console.error(e); }
            });
        },
        // ------------------------------

        request: async (args) => {
            const { method, params } = args;

            // 1. 扩展方法列表：必须包含切换链和添加链
            const nativeMethods = [
                'eth_requestAccounts',
                'eth_accounts',
                'eth_chainId',
                'net_version',
                'personal_sign',
                'eth_signTypedData_v4',
                'eth_sendTransaction',
                'wallet_switchEthereumChain', // 新增
                'wallet_addEthereumChain',    // 新增
                'eth_call',              // 合约读取（查余额、查授权等）
                'eth_estimateGas',       // 预估 Gas（发交易前必调）
                'eth_getBalance',        // 获取原生币余额
                'eth_gasPrice',          // 获取当前油价
                'eth_blockNumber',       // 获取最新区块
                'eth_getTransactionCount',// 获取 Nonce（发交易前必调）
                'eth_getTransactionReceipt', // 查询交易结果
                'eth_getBlockByNumber'        // 根据区块号查询区块详情
            ];

            if (nativeMethods.includes(method)) {
                return new Promise((resolve, reject) => {
                    const requestId = Math.random().toString(36).substring(2, 12);
                    _callbacks.set(requestId, { resolve, reject });

                    const payload = {
                        id: requestId,
                        method: method,
                        params: params || []
                    };

                    if (window.AndroidHost && window.AndroidHost.callNative) {
                        window.AndroidHost.callNative(JSON.stringify(payload));
                    } else {
                        reject(new Error("AndroidHost not found"));
                    }
                });
            }

            return Promise.reject(new Error(`Method ${method} not supported`));
        },

        // --- 4. 兼容性旧方法 (很多 DApp 内部断言会检查这些) ---
                sendAsync: function(payload, callback) {
                    if (!callback) return this.request(payload);
                    this.request(payload)
                        .then(result => callback(null, { id: payload.id, jsonrpc: "2.0", result }))
                        .catch(err => callback(err));
                },
                send: function(payload, callback) {
                    if (typeof callback === 'function') {
                        return this.sendAsync(payload, callback);
                    }
                    // 处理同步调用（如读取 chainId）
                    const { method } = payload;
                    if (method === 'eth_chainId') return { id: payload.id, jsonrpc: "2.0", result: this.chainId };
                    if (method === 'eth_accounts') return { id: payload.id, jsonrpc: "2.0", result: this.selectedAddress ? [this.selectedAddress] : [] };
                    return this.request(payload);
                },

        _onResponse: function(requestId, resultData, errorData) {
            const callback = _callbacks.get(requestId);

          // 检查类型
                console.log("是否为数组:", Array.isArray(resultData));
                     // --- 插入日志开始 ---
                                console.log(`[Provider] 收到原生回调:
                                    ID: ${requestId}
                                    成功数据: ${JSON.stringify(resultData)}
                                    错误数据: ${JSON.stringify(errorData)}`);
                                // --- 插入日志结束 ---

            if (!callback) return;
            if (errorData) {
                // 如果是对象则直接传，如果是字符串则解析后再传
                callback.reject(typeof errorData === 'string' ? JSON.parse(errorData) : errorData);
            } else {
                callback.resolve(resultData);
            }
            _callbacks.delete(requestId);
        },

        enable: function() { return this.request({ method: 'eth_requestAccounts' }); }
    };

    window.ethereum = provider;
    // 使用Object.defineProperty防止后续脚本（如AlphaWallet init.js）覆盖window.ethereum
    try {
        Object.defineProperty(window, 'ethereum', {
            value: provider,
            writable: false,
            configurable: false,
            enumerable: true
        });
    } catch(e) {
        // Fallback: 如果defineProperty失败，保留直接赋值的结果
    }
    window.dispatchEvent(new Event('ethereum#initialized'));
})();
