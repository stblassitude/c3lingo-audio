/*
 * Bridge between the c3lingo listen frontend and the native player, implementing
 * version 2 of the native bridge protocol (docs/playback-api.md in listen-frontend).
 *
 * Injected at document start. Page → app: window.c3lingoNative.ready(), play(channel),
 * stop() and setMuted(muted). App → page: "c3lingo:state" events with the full state,
 * dispatched through window.__c3lingoNativeState().
 *
 * Only pages that declare <meta name="c3lingo-native-bridge" content="2"> see
 * window.c3lingoNative; any other page is left alone and plays audio itself. The meta
 * tag hasn't been parsed yet when this runs, so it is checked on access.
 */
(function () {
    "use strict";

    const PROTOCOL_VERSION = "2";

    const handler = window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.c3lingo;
    if (!handler)
        return;

    const post = (message) => handler.postMessage(message);

    function channelInfo(channel) {
        return {
            name: String(channel.name),
            displayName: channel.displayName ? String(channel.displayName) : undefined,
            endpoint: channel.endpoint ? String(channel.endpoint) : undefined,
            location: channel.location ? String(channel.location) : undefined,
        };
    }

    const bridge = Object.freeze({
        ready: () => post({ cmd: "ready" }),
        play: (channel) => post({ cmd: "play", channel: channelInfo(channel) }),
        stop: () => post({ cmd: "stop" }),
        setMuted: (muted) => post({ cmd: "setMuted", muted: !!muted }),
    });

    function pageSupportsBridge() {
        const meta = document.querySelector('meta[name="c3lingo-native-bridge"]');
        return !!meta && meta.content.trim() === PROTOCOL_VERSION;
    }

    Object.defineProperty(window, "c3lingoNative", {
        enumerable: true,
        get: () => pageSupportsBridge() ? bridge : undefined,
    });

    // Called by the app with the full state.
    window.__c3lingoNativeState = function (detail) {
        window.dispatchEvent(new CustomEvent("c3lingo:state", { detail: detail }));
    };
})();
