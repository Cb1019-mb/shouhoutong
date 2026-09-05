/* 全局外观设置:每页引入,读 localStorage 即时应用(暗色主题/字号) */
(function () {
    try {
        const theme = localStorage.getItem('sht.theme');
        if (theme === 'dark') document.documentElement.dataset.theme = 'dark';
        const fontsize = localStorage.getItem('sht.fontsize');
        if (fontsize === 'large') document.documentElement.dataset.fontsize = 'large';
    } catch (e) { /* localStorage 不可用时静默 */ }
})();
