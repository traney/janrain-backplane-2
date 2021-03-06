/*
 * Copyright (c) 2006-2011 Echo <solutions@aboutecho.com>. All rights reserved.
 * You may copy and modify this script as long as the above copyright notice,
 * this condition and the following disclaimer is left intact.
 * This software is provided by the author "AS IS" and no warranties are
 * implied, including fitness for a particular purpose. In no event shall
 * the author be liable for any damages arising in any way out of the use
 * of this software, even if advised of the possibility of such damage.
 * $Id: backplane.js 32046 2011-03-31 08:53:15Z jskit $
 */

window.Backplane = window.Backplane || (function() {
    // Backplane is a function that accepts a function to be run onInit
    var BP = function(fn) {
        if (Backplane.getChannelID()) fn();
        else {
            Backplane.onInit = (function() {
                var original_onInit = Backplane.onInit;
                return function() {
                    original_onInit();
                    fn();
                };
            })();
        }
    };
    BP.log = function(msg) {
        if (window.console && window.console.log) {
            console.log("Backplane: " + msg);
        }
    }
    BP.warn = function(msg) {
        if (window.console && window.console.warn) {
            console.warn("Backplane WARNING: " + msg)
        }
    }
    BP.error = function(msg) {
        if (window.console && window.console.error) {
            console.error("Backplane ERROR: " + msg);
        }
    }
    BP.version = "1.2.5";
    BP.channelByBus = {};
    BP.config = {};
    BP.initialized = false;
    BP.runRequests = false;
    BP.firstFrameReceived = false;
    BP.cachedMessages = {};
    BP.cachedMessagesIndex = [];
    BP.cacheMax = 5;
    BP.subscribers = {};
    BP.serverChannel = true;
    BP.awaiting = {
        "since": 0,
        "until": 0,
        "queue": []
    };
    BP.intervals = {
        "min": 1,
        "frequent": 5,
        "regular": 60,
        "slowdown": 120
    };
    BP.onInit = function() {};
    return BP;
})();

/**
 * Initializes the backplane library
 *
 * @param {Object} config - Hash with configuration parameters.
 *   Possible hash keys:
 *     serverBaseURL (required) - Base URL of Backplane Server
 *     busName (required) - Customer's backplane bus name
 *     serverChannel (optional) - default to true for best security
 *     channelExpires (optional) - set backplane-channel cookie life span
 *     initFrameFilter (optional) - function to filter the first message frame
 *     cacheMax (optional) - how many messages to cache for late arriving widgets
 */
Backplane.init = function(config) {
    config = config || {};
    if (this.initialized || !config.serverBaseURL || !config.busName) return false;
    this.timers = {};
    this.config = config;
    this.config.serverBaseURL = this.normalizeURL(config.serverBaseURL);

    this.channelByBus = this.getCookieChannels();
    this.cacheMax = config.cacheMax || this.cacheMax;
    if (typeof config.serverChannel !== "undefined") {
       this.serverChannel = config.serverChannel;
    }

    if (typeof this.config.channelExpires == "undefined") {
        var d = new Date();
        d.setFullYear(d.getFullYear() + 5);
        this.config.channelExpires = d.toGMTString();
    }

    // XXX: this can be removed after couple of weeks
    this.renameOldCache();

    if (this.getChannelName()) {
        this.finishInit(false);
    } else {
        this.invalidateCache();
        this.fetchNewChannel();
    }
    return true;
};

Backplane.renameOldCache = function() {
    // Check for old cache
    if (!window.localStorage) return;
    var v1 = localStorage.getItem("cacheExpires");
    var v2 = localStorage.getItem("cachedMessages");
    var v3 = localStorage.getItem("cachedMessagesIndex");
    if (!v1 || !v2 || !v3) return;

    localStorage.setItem("backplaneCacheExpires", v1);
    localStorage.setItem("backplaneCachedMessages", v2);
    localStorage.setItem("backplaneCachedMessagesIndex", v3);

    localStorage.removeItem("cacheExpires");
    localStorage.removeItem("cachedMessages");
    localStorage.removeItem("cachedMessagesIndex");
}

/**
 * Subscribes to messages from Backplane server
 *
 * @param {Function} Callback - Callback function which accepts backplane messages
 * @returns Subscription ID which can be used later for unsubscribing
 */
Backplane.subscribe = function(callback) {
    if (!this.initialized) return false;
    if (!this.checkSubscribers()) {
        // No subscribers means no request loop is running;
        // start one up.
        this.runRequests = true;
        this.request();
    }
    var id = (new Date()).valueOf() + Math.random();
    this.subscribers[id] = callback;
    //if the first frame has already been processed, catch this widget up
    if (this.firstFrameReceived) {
        for (var i=0; i<this.cachedMessagesIndex.length; i++) {
            callback(this.cachedMessages[this.cachedMessagesIndex[i]]);
        }
    }
    return id;
};

/**
 * Removes specified subscription
 *
 * @param {Integer} Subscription ID
 */
Backplane.unsubscribe = function(subscriptionID) {
    if (!this.initialized || !subscriptionID) return false;
    delete this.subscribers[subscriptionID];
    if (!this.checkSubscribers()) {
        // No more subscribers left; go to sleep.
        this.runRequests = false;
    }
};

/**
 * Returns channel ID (like http://backplane.customer.com/v1/bus/customer.com/channel/8ec92f459fa70b0da1a40e8fe70a0bc8)
 *
 * @returns Backplane channel ID
 */
Backplane.getChannelID = function() {
    if (!this.initialized) return false;
    return this.config.channelID;
};

/**
 * Notifies backplane library about the fact that subscribers are going
 * to receive backplane messages of any of the specified types
 *
 * @param {Array} List of expected backplane message types
 */
Backplane.expectMessages = function(types) {
    this.expectMessagesWithin(60, types);
};

/**
 * Notifies backplane library about the fact that subscribers are going
 * to receive backplane messages within specified time interval.
 *
 * @param {Integer} TimeInterval Time interval in seconds
 */
Backplane.expectMessagesWithin = function(interval, types) {
    if (!this.initialized || !interval) return false;
    this.awaiting.since = this.getTS();
    this.awaiting.interval = interval;
    // we should wait entire interval if no types were specified
    this.awaiting.nonstop = !types;
    if (types) {
        types = typeof types == "string" ? [types] : types;
        this.awaiting.queue.push(types);
    }
    var until = this.awaiting.since + interval;
    if (until > this.awaiting.until) {
        this.awaiting.until = until;
    }
    this.request();
};

/**
 * Internal functions
 */
Backplane.finishInit = function (channelName) {
    if (channelName) {
        this.channelByBus[this.config.busName] = channelName;
    }

    this.setCookieChannels();
    this.config.channelName = this.getChannelName();
    this.config.channelID = this.generateChannelID();
    this.initialized = true;
    this.onInit();
    this.request();
};

Backplane.generateChannelID = function() {
    return this.config.serverBaseURL + "/bus/" + this.config.busName + "/channel/" + this.config.channelName;
};

Backplane.getChannelName = function() {
    if (!this.initialized) return false;
    if (!this.channelByBus[this.config.busName]) {
        if (this.serverChannel) {
            return false;
        } else {
            this.invalidateCache();
            this.channelByBus[this.config.busName] = (new Date()).valueOf().toString() + Math.random().toString().substr(2, 5);
            this.setCookieChannels();
        }
    }
    return this.channelByBus[this.config.busName];
};

Backplane.getTS = function() {
    return Math.round((new Date()).valueOf() / 1000);
};

Backplane.checkSubscribers = function() {
	var name;
	for (name in this.subscribers) {
		return true;
	}
	return false;
};

Backplane.getCookieChannels = function() {
    var match = (document.cookie || "").match(/backplane-channel=(.*?)(?:$|;)/);
    if (!match || !match[1]) return {};
    var channelByBus = {};
    var parts = match[1].split("|");
    for (var i = 0; i < parts.length; i++) {
        var m = parts[i].split(":");
        channelByBus[decodeURIComponent(m[0])] = decodeURIComponent(m[1]);
    }
    return channelByBus;
};

Backplane.setCookieChannels = function() {
    var parts = [];
    for (var i in this.channelByBus) {
        if (this.channelByBus.hasOwnProperty(i)) {
            parts.push(encodeURIComponent(i) + ":" + encodeURIComponent(this.channelByBus[i]));
        }
    }

    document.cookie = "backplane-channel=" + parts.join("|") + ";expires=" + this.config.channelExpires + ";path=/";
};

Backplane.resetCookieChannel = function() {
    delete this.channelByBus[this.config.busName];
    this.invalidateCache();
    this.setCookieChannels();
    if (this.serverChannel) {
        this.fetchNewChannel();
    } else {
        this.config.channelName = this.getChannelName();
        this.config.channelID = this.generateChannelID();
    }
};

Backplane.fetchNewChannel = function() {
    var oldScript = document.getElementById('fetchChannelId'); 
    // cleanup old script if it exists to prevent memory leak
    while (oldScript && oldScript.parentNode) {
        oldScript.parentNode.removeChild(oldScript);
        for (var prop in oldScript) {
            delete oldScript[prop];
        }
	    oldScript = document.getElementById('fetchChannelId')
    }

    var script = document.createElement("script");
    script.src =  this.config.serverBaseURL + "/bus/" + this.config.busName + "/channel/new?callback=Backplane.finishInit" + "&rnd=" + Math.random();
    script.type = "text/javascript";
    script.id = 'fetchChannelId';
    var firstScript = document.getElementsByTagName("script")[0];
    firstScript.parentNode.insertBefore(script, firstScript);

};

Backplane.invalidateCache = function() {
    Backplane.log("removing cached backplane messages");
    this.cachedMessages = {};
    this.cachedMessagesIndex = [];

    if (window.localStorage) {
        localStorage.removeItem("backplaneCacheExpires");
        localStorage.removeItem("backplaneCachedMessages");
        localStorage.removeItem("backplaneCachedMessagesIndex");
    }
};

Backplane.normalizeURL = function(rawURL) {
    return rawURL.replace(/^\s*(https?:\/\/)?(.*?)[\s\/]*$/, function(match, proto, uri){
        return (proto || window.location.protocol + "//") + uri;
    });
};

/*
 * Calculate amount of time after which the request will be sent.
 *
 * If a message is expected, we should poll more frequently, gradually
 * slowing down, until we reach the maximum interval for frequent
 * polling for expected messages (this.intervals.frequent).  After
 * that, continue to slow down until we reach the regular interval.
 */
Backplane.calcTimeout = function() {
    var timeout, ts = this.getTS();
    if (ts < this.awaiting.until) {
        // stop frequent polling as soon as all the necessary messages received
        if (!this.awaiting.nonstop && !this.awaiting.queue.length) {
            this.awaiting.until = ts;
            return this.calcTimeout();
        }
        var relative = ts - this.awaiting.since;
        var limit = this.intervals.frequent - this.intervals.min;
        // we should reach this.intervals.frequent at the end
        timeout = this.intervals.min +
            Math.round(limit * relative / this.awaiting.interval);
    } else if (ts < this.awaiting.until + this.intervals.slowdown) {
        var relative = ts - this.awaiting.until;
        var limit = this.intervals.regular - this.intervals.frequent;
        // we should reach this.intervals.regular at the end
        timeout = this.intervals.frequent +
            Math.round(limit * relative / this.intervals.slowdown);
    } else {
        timeout = typeof this.since == "undefined" ? 0 : this.intervals.regular;
        this.awaiting.nonstop = false;
    }
    return timeout * 1000;
};

Backplane.request = function() {
    var self = this;
    if (!this.initialized || !this.runRequests) return false;
    this.stopTimer("regular");
    this.stopTimer("watchdog");
    this.timers.regular = setTimeout(function() {
        // if no response in the reasonable time just restart request
        self.timers.watchdog = setTimeout(function() {
            self.request();
        }, 5000);

        // if no since parameter exists, check cache and play those back
        // rather than hitting the server
        if (window.localStorage && !self.since) {
            // should cache be expired?
            var cacheExpiresString = localStorage.getItem("backplaneCacheExpires");
            if (cacheExpiresString) {
               var cacheExpires = Date.parse(cacheExpiresString);
               var now = new Date();
               if (now > cacheExpires) {
                 self.log("cache expired");
                 self.invalidateCache();
               } else {
                 self.cachedMessages = JSON.parse(localStorage.getItem("backplaneCachedMessages"));
                 self.cachedMessagesIndex = JSON.parse(localStorage.getItem("backplaneCachedMessagesIndex"));
                 if (self.cachedMessages) {
                    var messages = []; 
                    for (var i=0; i<self.cachedMessagesIndex.length; i++) {
                       messages[i] = self.cachedMessages[self.cachedMessagesIndex[i]];
                    }
                    self.log(messages.length + " message(s) in cache");
                    self.response(messages);
                    return;
                 }
               }
            }
        } 

        var script = document.createElement("script");
        script.type = "text/javascript";
        script.charset = "utf-8";
        script.src = self.config.channelID + "?callback=Backplane.response" +
            (self.since ? "&since=" + encodeURIComponent(self.since) : "") +
            "&rnd=" + Math.random();
        var container = document.getElementsByTagName("head")[0] || document.documentElement;
        container.insertBefore(script, container.firstChild);
        script.onload = script.onreadystatechange = function() {
            var state = script.readyState;
            if (!state || state === "loaded" || state === "complete") {
                script.onload = script.onreadystatechange = null;
                if (script.parentNode) {
                    script.parentNode.removeChild(script);
                }
            }
        };
    }, this.calcTimeout());
};

Backplane.response = function(messages) {
    var self = this;
    this.stopTimer("watchdog");
    messages = messages || [];
    var since = messages.length ? messages[messages.length - 1].id : this.since;
    if (typeof this.since == "undefined") {
        if (typeof this.config.initFrameFilter != "undefined") {
            messages = this.config.initFrameFilter(messages);
        } else {
            messages = [];
        }
    }

    this.since = since || "";
    for (var i = 0; i < messages.length; i++) {
        // notify subscribers
        for (var j in this.subscribers) {
            if (this.subscribers.hasOwnProperty(j)) {
                this.subscribers[j](messages[i].message);
            }
        }
        // stash message in cache
        if (this.cacheMax > 0) {
            if (!this.cachedMessages.hasOwnProperty([messages[i].id])) {
                this.cachedMessages[messages[i].id] = messages[i];
                this.cachedMessagesIndex.push(messages[i].id);
            }
            if (this.cachedMessagesIndex.length > this.cacheMax) {
                delete this.cachedMessages[this.cachedMessagesIndex[0]];
                this.cachedMessagesIndex.splice(0,1);
            }
            if (window.localStorage) {
                localStorage.setItem("backplaneCachedMessages", JSON.stringify(this.cachedMessages));
                localStorage.setItem("backplaneCachedMessagesIndex", JSON.stringify(this.cachedMessagesIndex));

                var expiresDate = new Date();
                expiresDate.setDate(expiresDate.getDate()+7);
                localStorage.setItem("backplaneCacheExpires", expiresDate.toUTCString()); 
            }
        }

        // clean up awaiting specific events queue
        var queue = [];
        for (var k = 0; k < this.awaiting.queue.length; k++) {
            var satisfied = false;
            for (var l = 0; l < this.awaiting.queue[k].length; l++) {
                if (this.awaiting.queue[k][l] == messages[i].message.type) {
                    satisfied = true;
                }
            }
            if (!satisfied) queue.push(this.awaiting.queue[k]);
        }
        this.awaiting.queue = queue;
    }
    this.firstFrameReceived = true;
    this.request();
};

Backplane.stopTimer = function(name) {
    var timer = this.timers[name];
    if (timer) clearTimeout(timer);
};
