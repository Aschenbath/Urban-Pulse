import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { spawn } from "node:child_process";

const repo = path.resolve(import.meta.dirname, "..");
const webRoot = path.join(repo, "nginx-1.18.0/html/review");
const outDir = path.join(repo, "docs/screenshots");
const chromePath = "C:/Program Files/Google/Chrome/Application/chrome.exe";
const profileDir = "D:/Codex/tmp_toDel/chrome-review-showcase";

fs.mkdirSync(outDir, { recursive: true });

const showcaseImages = [
  "/imgs/showcase/bistro.jpg",
  "/imgs/showcase/cafe.jpg",
  "/imgs/showcase/restaurant.jpg",
  "/imgs/showcase/dinner.jpg",
  "/imgs/showcase/brunch.jpg"
];

const mimeTypes = new Map([
  [".html", "text/html; charset=utf-8"],
  [".css", "text/css; charset=utf-8"],
  [".js", "application/javascript; charset=utf-8"],
  [".png", "image/png"],
  [".svg", "image/svg+xml"],
  [".jpg", "image/jpeg"],
  [".jpeg", "image/jpeg"],
  [".ico", "image/x-icon"],
  [".woff", "font/woff"],
  [".ttf", "font/ttf"]
]);

function apiData(pathname) {
  if (pathname === "/api/shop-type/list") {
    return [
      { id: 1, name: "Food", icon: "/types/food.svg", sort: 1 },
      { id: 2, name: "Cafe", icon: "/types/cafe.svg", sort: 2 },
      { id: 3, name: "Beauty", icon: "/types/beauty.svg", sort: 3 },
      { id: 10, name: "Nails", icon: "/types/nails.svg", sort: 4 },
      { id: 5, name: "Massage", icon: "/types/massage.svg", sort: 5 },
      { id: 6, name: "KTV", icon: "/types/ktv.svg", sort: 6 },
      { id: 7, name: "Family", icon: "/types/family.svg", sort: 7 },
      { id: 8, name: "Bar", icon: "/types/bar.svg", sort: 8 },
      { id: 9, name: "Party", icon: "/types/party.svg", sort: 9 },
      { id: 4, name: "Fitness", icon: "/types/fitness.svg", sort: 10 }
    ];
  }

  if (pathname === "/api/blog/hot") {
    return [
      {
        id: 1,
        title: "New brunch spot with calm terrace seats",
        images: showcaseImages[0],
        icon: "/imgs/icons/default-icon.png",
        name: "Mia",
        liked: 248,
        isLike: true,
        shopId: 1,
        userId: 1,
        content: "Stable dishes and a quiet terrace.",
        createTime: new Date().toISOString()
      },
      {
        id: 2,
        title: "Small cafe for workday coffee breaks",
        images: showcaseImages[1],
        icon: "/imgs/icons/user5-icon.png",
        name: "Alex",
        liked: 176,
        isLike: false,
        shopId: 2,
        userId: 2,
        content: "A small cafe for weekends.",
        createTime: new Date().toISOString()
      }
    ];
  }

  if (pathname === "/api/shop/1") {
    return {
      name: "Morrow Bistro",
      score: 47,
      comments: 128,
      images: [showcaseImages[0], showcaseImages[1], showcaseImages[2]].join(","),
      address: "88 Tianhe West Road",
      openHours: "10:00 - 22:00",
      avgPrice: 86
    };
  }

  if (pathname === "/api/voucher/list/1") {
    const now = new Date();
    const later = new Date(Date.now() + 86400000 * 7);
    return [
      {
        id: 1,
        type: 1,
        title: "100 RMB Voucher",
        subTitle: "Store-wide discount coupon",
        payValue: 6900,
        actualValue: 10000,
        stock: 42,
        beginTime: now.toISOString(),
        endTime: later.toISOString()
      },
      {
        id: 2,
        type: 0,
        title: "Set Meal Coupon",
        subTitle: "Weekday lunch available",
        payValue: 12800,
        actualValue: 20000,
        stock: 99,
        beginTime: now.toISOString(),
        endTime: later.toISOString()
      }
    ];
  }

  return [];
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, "http://127.0.0.1");
  if (url.pathname.startsWith("/api/")) {
    res.writeHead(200, { "content-type": "application/json; charset=utf-8" });
    res.end(JSON.stringify({ success: true, data: apiData(url.pathname) }));
    return;
  }

  const relPath = decodeURIComponent(url.pathname.replace(/^\//, "")) || "index.html";
  const filePath = path.normalize(path.join(webRoot, relPath));
  if (!filePath.startsWith(webRoot)) {
    res.writeHead(403);
    res.end("Forbidden");
    return;
  }

  fs.readFile(filePath, (err, data) => {
    if (err) {
      res.writeHead(404);
      res.end("Not found");
      return;
    }
    res.writeHead(200, {
      "content-type": mimeTypes.get(path.extname(filePath).toLowerCase()) || "application/octet-stream"
    });
    res.end(data);
  });
});

function wait(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

async function requestJson(url) {
  for (let i = 0; i < 80; i += 1) {
    try {
      const response = await fetch(url);
      if (!response.ok) {
        throw new Error(String(response.status));
      }
      return await response.json();
    } catch {
      await wait(100);
    }
  }
  throw new Error(`DevTools endpoint not ready: ${url}`);
}

class Cdp {
  constructor(wsUrl) {
    this.ws = new WebSocket(wsUrl);
    this.id = 0;
    this.pending = new Map();
  }

  async open() {
    await new Promise((resolve, reject) => {
      this.ws.addEventListener("open", resolve, { once: true });
      this.ws.addEventListener("error", reject, { once: true });
    });
    this.ws.addEventListener("message", event => {
      const message = JSON.parse(event.data);
      if (message.id && this.pending.has(message.id)) {
        const pending = this.pending.get(message.id);
        this.pending.delete(message.id);
        if (message.error) {
          pending.reject(new Error(JSON.stringify(message.error)));
        } else {
          pending.resolve(message.result || {});
        }
      }
    });
  }

  send(method, params = {}) {
    const id = this.id += 1;
    this.ws.send(JSON.stringify({ id, method, params }));
    return new Promise((resolve, reject) => this.pending.set(id, { resolve, reject }));
  }

  close() {
    this.ws.close();
  }
}

async function createPage(url) {
  const version = await requestJson("http://127.0.0.1:9224/json/version");
  const browser = new Cdp(version.webSocketDebuggerUrl);
  await browser.open();
  const { targetId } = await browser.send("Target.createTarget", { url });
  browser.close();

  for (let i = 0; i < 50; i += 1) {
    const list = await requestJson("http://127.0.0.1:9224/json/list");
    const target = list.find(item => item.id === targetId);
    if (target) {
      return target;
    }
    await wait(100);
  }
  throw new Error("Chrome target not found");
}

async function capture(url, fileName, prepareExpression) {
  const target = await createPage(url);
  const page = new Cdp(target.webSocketDebuggerUrl);
  await page.open();
  await page.send("Page.enable");
  await page.send("Runtime.enable");
  await page.send("Emulation.setDeviceMetricsOverride", {
    width: 390,
    height: 844,
    deviceScaleFactor: 2,
    mobile: true
  });
  await page.send("Emulation.setEmulatedMedia", {
    features: [{ name: "prefers-color-scheme", value: "light" }]
  });
  await wait(2600);
  if (prepareExpression) {
    await page.send("Runtime.evaluate", {
      expression: prepareExpression,
      awaitPromise: true,
      returnByValue: true
    });
    await wait(600);
  }
  const screenshot = await page.send("Page.captureScreenshot", {
    format: "png",
    fromSurface: true,
    captureBeyondViewport: false
  });
  fs.writeFileSync(path.join(outDir, fileName), Buffer.from(screenshot.data, "base64"));
  page.close();
}

const homePrepare = `
(async () => {
  document.title = 'Review System';
  document.querySelectorAll('.el-message').forEach(el => el.remove());
  const city = document.querySelector('.city-btn');
  if (city) city.childNodes[0].nodeValue = 'HZ ';
  const input = document.querySelector('.el-input__inner');
  if (input) input.placeholder = 'Search restaurants, cafes, notes';
})()
`;

const shopPrepare = `
(async () => {
  document.title = 'Review System - Shop';
  document.querySelectorAll('.el-message').forEach(el => el.remove());
  const set = (selector, text) => {
    const el = document.querySelector(selector);
    if (el) el.textContent = text;
  };
  set('.shop-rate > span', '128 reviews');
  set('.shop-rate-info', 'Taste 4.9  Space 4.8  Service 4.7');
  set('.shop-rank span', 'Top rated in Tianhe District');
  set('.shop-open-time div:nth-of-type(1)', 'Open Hours');
  set('.line-right', 'Details >');
  set('.shop-voucher > div:first-child span:nth-child(1)', 'Deal');
  set('.shop-voucher > div:first-child span:nth-child(2)', 'Coupons');
  set('.comments-head div:first-child', 'User Reviews (128)');
  document.querySelectorAll('.voucher-btn').forEach((el, index) => {
    el.textContent = index === 0 ? 'Flash Sale' : 'Buy';
  });
  document.querySelectorAll('.seckill-stock').forEach(el => {
    el.innerHTML = 'Stock <span>42</span> left';
  });
  const tags = ['Good taste', 'Quiet space', 'Warm service', 'Dinner spot', 'Value', 'Parking'];
  document.querySelectorAll('.tag').forEach((el, index) => {
    el.textContent = tags[index] || el.textContent;
  });
})()
`;

await new Promise(resolve => server.listen(18080, "127.0.0.1", resolve));
const chrome = spawn(chromePath, [
  "--headless=new",
  "--disable-gpu",
  "--hide-scrollbars",
  "--no-first-run",
  "--no-default-browser-check",
  "--remote-debugging-port=9224",
  `--user-data-dir=${profileDir}`,
  "about:blank"
], { stdio: "ignore" });

try {
  await requestJson("http://127.0.0.1:9224/json/version");
  await capture("http://127.0.0.1:18080/index.html", "home.png", homePrepare);
  await capture("http://127.0.0.1:18080/shop-detail.html?id=1", "shop-detail.png", shopPrepare);
} finally {
  chrome.kill();
  server.close();
}
