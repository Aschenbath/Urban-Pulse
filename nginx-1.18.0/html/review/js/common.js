// let commonURL = "http://192.168.50.115:8081";
let commonURL = "/api";
// 设置后台服务地址
axios.defaults.baseURL = commonURL;
axios.defaults.timeout = 5000;
// request拦截器，将用户token放入头中
let token = sessionStorage.getItem("token");
axios.interceptors.request.use(
  config => {
    if(token) config.headers['authorization'] = token
    return config
  },
  error => {
    console.log(error)
    return Promise.reject(error)
  }
)
axios.interceptors.response.use(function (response) {
  // 判断执行结果
  if (!response.data.success) {
    return Promise.reject(response.data.errorMsg)
  }
  return response.data;
}, function (error) {
  // 一般是服务端异常或者网络异常
  console.log(error)
  if(error.response && error.response.status == 401){
    // 未登录，跳转
    setTimeout(() => {
      location.href = "/login.html"
    }, 200);
    return Promise.reject("请先登录");
  }
  return Promise.reject("服务器异常");
});
axios.defaults.paramsSerializer = function(params) {
  let p = "";
  Object.keys(params).forEach(k => {
    if(params[k]){
      p = p + "&" + k + "=" + params[k]
    }
  })
  return p;
}
const util = {
  commonURL,
  getUrlParam(name) {
    let reg = new RegExp("(^|&)" + name + "=([^&]*)(&|$)", "i");
    let r = window.location.search.substr(1).match(reg);
    if (r != null) {
      return decodeURI(r[2]);
    }
    return "";
  },
  formatPrice(val) {
    if (typeof val === 'string') {
      if (isNaN(val)) {
        return null;
      }
      // 价格转为整数
      const index = val.lastIndexOf(".");
      let p = "";
      if (index < 0) {
        // 无小数
        p = val + "00";
      } else if (index === p.length - 2) {
        // 1位小数
        p = val.replace("\.", "") + "0";
      } else {
        // 2位小数
        p = val.replace("\.", "")
      }
      return parseInt(p);
    } else if (typeof val === 'number') {
      if (!val) {
        return null;
      }
      const s = val + '';
      if (s.length === 0) {
        return "0.00";
      }
      if (s.length === 1) {
        return "0.0" + val;
      }
      if (s.length === 2) {
        return "0." + val;
      }
      const i = s.indexOf(".");
      if (i < 0) {
        return s.substring(0, s.length - 2) + "." + s.substring(s.length - 2)
      }
      const num = s.substring(0, i) + s.substring(i + 1);
      if (i === 1) {
        // 1位整数
        return "0.0" + num;
      }
      if (i === 2) {
        return "0." + num;
      }
      if (i > 2) {
        return num.substring(0, i - 2) + "." + num.substring(i - 2)
      }
    }
  }
}

util.clearSession = function() {
  token = null;
  sessionStorage.removeItem("token");
  sessionStorage.removeItem("userInfo");
}

util.localLogout = function(redirectTo) {
  util.clearSession();
  location.href = redirectTo || "/";
}

util.notifyFeatureUnavailable = function(vm, message) {
  if (vm && vm.$message) {
    vm.$message.warning(message || "当前演示版暂未开放该功能");
  }
}

util.rememberUserSummary = function(user) {
  if (!user || !user.id) {
    return;
  }
  const summary = {
    id: user.id,
    nickName: user.nickName || user.name || "用户",
    icon: user.icon || "/imgs/icons/default-icon.png"
  };
  sessionStorage.setItem("userSummary:" + summary.id, JSON.stringify(summary));
}

util.getRememberedUserSummary = function(id) {
  if (!id) {
    return null;
  }
  const raw = sessionStorage.getItem("userSummary:" + id);
  if (!raw) {
    return null;
  }
  try {
    return JSON.parse(raw);
  } catch (e) {
    return null;
  }
}

util.rememberBlogCard = function(blog) {
  if (!blog || !blog.id) {
    return;
  }
  const snapshot = {
    id: blog.id,
    title: blog.title || "",
    images: Array.isArray(blog.images) ? blog.images.join(",") : (blog.images || ""),
    liked: blog.liked || 0,
    isLike: !!blog.isLike,
    icon: blog.icon || "/imgs/icons/default-icon.png",
    name: blog.name || "",
    userId: blog.userId,
    shopId: blog.shopId,
    content: blog.content || "",
    createTime: blog.createTime || ""
  };
  sessionStorage.setItem("blogCard:" + snapshot.id, JSON.stringify(snapshot));
  sessionStorage.setItem("lastBlogCard", JSON.stringify(snapshot));
  util.rememberUserSummary({
    id: snapshot.userId,
    nickName: snapshot.name,
    icon: snapshot.icon
  });
}

util.getRememberedBlogCard = function(id) {
  const keys = id ? ["blogCard:" + id, "lastBlogCard"] : ["lastBlogCard"];
  for (let i = 0; i < keys.length; i++) {
    const raw = sessionStorage.getItem(keys[i]);
    if (!raw) {
      continue;
    }
    try {
      const blog = JSON.parse(raw);
      if (!id || String(blog.id) === String(id)) {
        return blog;
      }
    } catch (e) {
    }
  }
  return null;
}

const defaultShopTypes = [
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

util.resolveTypeIcon = function(icon) {
  if (!icon) {
    return "/imgs/icons/default-icon.png";
  }
  if (icon.startsWith("/imgs/")) {
    return icon;
  }
  if (icon.startsWith("/")) {
    return "/imgs" + icon;
  }
  return "/imgs/" + icon.replace(/^\/+/, "");
}

util.resolveShopTypes = function(types) {
  const source = Array.isArray(types) && types.length ? types : defaultShopTypes;
  return source
    .slice()
    .sort((a, b) => (a.sort || a.id) - (b.sort || b.id))
    .map(t => ({
      id: t.id,
      name: t.name,
      sort: t.sort,
      icon: util.resolveTypeIcon(t.icon)
    }));
}

function mountTypeListFallback() {
  const typeList = document.querySelector(".type-list");
  if (!typeList) {
    return;
  }
  if (typeList.querySelectorAll(".type-box").length >= defaultShopTypes.length) {
    return;
  }

  typeList.innerHTML = "";
  util.resolveShopTypes([]).forEach(t => {
    const typeBox = document.createElement("div");
    typeBox.className = "type-box";
    typeBox.innerHTML =
      '<div class="type-view"><img src="' + t.icon + '" alt=""></div>' +
      '<div class="type-text">' + t.name + "</div>";
    typeBox.addEventListener("click", () => {
      location.href = "/shop-list.html?type=" + t.id + "&name=" + encodeURIComponent(t.name);
    });
    typeList.appendChild(typeBox);
  });
}

window.addEventListener("load", () => {
  setTimeout(mountTypeListFallback, 1500);
});
