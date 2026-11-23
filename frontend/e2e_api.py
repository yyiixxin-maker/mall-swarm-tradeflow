"""Real gateway integration checks. Adds only clearly named test data through business APIs."""
import argparse
import copy
from datetime import datetime
from decimal import Decimal
import json
from pathlib import Path
import secrets
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parent.parent
STATE = ROOT / "frontend/.runtime"
GATEWAY = "http://127.0.0.1:18201"
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))
RESULTS = []


def success(status, payload):
    return 200 <= status < 300 and isinstance(payload, dict) and payload.get("code") == 200


def api(path, method="GET", data=None, token=None, form=False):
    headers = {"Content-Type": "application/x-www-form-urlencoded" if form else "application/json"}
    if token:
        headers["Authorization"] = token
    body = None if data is None else (urllib.parse.urlencode(data).encode() if form else json.dumps(data).encode())
    request = urllib.request.Request(GATEWAY + path, data=body, headers=headers, method=method)
    try:
        response = OPENER.open(request, timeout=30)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        payload = json.loads(response.read().decode("utf-8"))
        if not success(response.code, payload):
            raise RuntimeError(f"{method} {path.split('?')[0]}: HTTP {response.code}, business code {payload.get('code')}")
        return payload.get("data")


def step(name, action):
    started = time.monotonic()
    try:
        value = action()
        RESULTS.append({"name": name, "result": "PASS", "seconds": round(time.monotonic() - started, 2)})
        print("PASS " + name, flush=True)
        return value
    except Exception:
        RESULTS.append({"name": name, "result": "FAIL"})
        raise


def check(condition, message):
    if not condition:
        raise AssertionError(message)


def authorization(data):
    check(bool(data.get("token")) and bool(data.get("tokenHead")), "Login has no usable token")
    return data["tokenHead"].strip() + " " + data["token"]


def run():
    sys.path.insert(0, str(ROOT / "environment"))
    import local
    values = local.credentials()
    fixture_path = STATE / "e2e-credentials.json"
    fixture = json.loads(fixture_path.read_text("utf-8")) if fixture_path.exists() else {"runId": uuid.uuid4().hex[:10]}
    def save():
        fixture_path.write_text(json.dumps(fixture, ensure_ascii=False, indent=2), "utf-8")
    admin = step("管理员真实登录", lambda: authorization(api("/mall-admin/admin/login", "POST", {
        "username": values["TRADEFLOW_ADMIN_USER"], "password": values["TRADEFLOW_ADMIN_PASSWORD"]})))
    step("管理员资料与权限", lambda: api("/mall-admin/admin/info", token=admin))
    products = step("真实商品列表", lambda: api("/mall-admin/product/list?pageNum=1&pageSize=5", token=admin))
    check(isinstance(products.get("list"), list) and products["total"] > 0, "No real product data")
    step("真实管理员列表", lambda: api("/mall-admin/admin/list?pageNum=1&pageSize=5", token=admin))
    step("真实订单列表", lambda: api("/mall-admin/order/list?pageNum=1&pageSize=5", token=admin))
    if "member" not in fixture:
        def register():
            member = {"username": "tf_h5_" + fixture["runId"], "password": secrets.token_urlsafe(24),
                      "telephone": "199" + f"{secrets.randbelow(100_000_000):08d}"}
            code = api("/mall-portal/sso/getAuthCode?" + urllib.parse.urlencode({"telephone": member["telephone"]}))
            api("/mall-portal/sso/register", "POST", dict(member, authCode=code), form=True)
            fixture["member"] = member
            save()
        step("通过注册业务接口新增独立测试会员", register)
    member = fixture["member"]
    member_token = step("会员真实登录", lambda: authorization(api("/mall-portal/sso/login", "POST", {
        "username": member["username"], "password": member["password"]}, form=True)))
    info = step("会员中心真实资料", lambda: api("/mall-portal/sso/info", token=member_token))
    fixture["memberId"] = info["id"]
    # Never persist the raw member response; it may contain a password hash.
    save()
    if "productId" not in fixture:
        def create():
            brands = api("/mall-admin/brand/listAll", token=admin)
            categories = api("/mall-admin/productCategory/list/withChildren", token=admin)
            category = next(child for parent in categories for child in parent.get("children", []) if child.get("id"))
            body = {"name": "TradeFlow联调商品-" + fixture["runId"], "productSn": "TF-E2E-" + fixture["runId"],
                    "brandId": brands[0]["id"], "brandName": brands[0]["name"], "productCategoryId": category["id"],
                    "productCategoryName": category["name"], "deleteStatus": 0, "publishStatus": 1, "verifyStatus": 1,
                    "newStatus": 0, "recommandStatus": 0, "promotionType": 0, "giftPoint": 0, "giftGrowth": 0,
                    "price": 19.9, "originalPrice": 19.9, "stock": 12, "lowStock": 1, "sale": 0, "sort": 0,
                    "subTitle": "仅用于真实接口联调，不代表实际售卖商品", "description": "前后端集成测试商品",
                    "pic": "http://127.0.0.1:15174/static/missing-face.png", "serviceIds": "", "unit": "件",
                    "skuStockList": [{"skuCode": "TF-SKU-" + fixture["runId"], "price": 19.9, "stock": 12, "lowStock": 1, "sale": 0, "spData": "[]"}]}
            for key in ("productLadderList", "productFullReductionList", "memberPriceList", "productAttributeValueList", "subjectProductRelationList", "prefrenceAreaProductRelationList"):
                body[key] = []
            check(api("/mall-admin/product/create", "POST", body, admin) == 1, "Product create changed no row")
            found = api("/mall-admin/product/list?" + urllib.parse.urlencode({"productSn": body["productSn"], "pageNum": 1, "pageSize": 10}), token=admin)
            product = next(row for row in found["list"] if row["productSn"] == body["productSn"])
            fixture.update(productId=product["id"], productSn=body["productSn"], productName=body["name"], productBody=body)
            save()
        step("通过管理业务接口新增真实商品和SKU", create)
    pid = fixture["productId"]
    def update():
        body = copy.deepcopy(fixture["productBody"])
        if not body.get("productAttributeCategoryId"):
            # Existing detail API requires an attribute category; use a real catalog template via admin APIs.
            for sample in products["list"]:
                template = api(f"/mall-admin/product/updateInfo/{sample['id']}", token=admin)
                if template.get("productAttributeCategoryId") and template.get("skuStockList"):
                    for key in ("productAttributeCategoryId", "productCategoryId", "productCategoryName", "brandId", "brandName"):
                        body[key] = template.get(key)
                    body["productAttributeValueList"] = [{"productAttributeId": row["productAttributeId"], "value": row["value"]} for row in template.get("productAttributeValueList", [])]
                    body["skuStockList"] = api(f"/mall-admin/sku/{pid}", token=admin)
                    body["skuStockList"][0]["spData"] = template["skuStockList"][0]["spData"]
                    break
            check(bool(body.get("productAttributeCategoryId")), "No usable real attribute template")
        body["subTitle"] = "已通过真实商品修改联调"
        if body["skuStockList"][0].get("id") is None:
            body["skuStockList"] = api(f"/mall-admin/sku/{pid}", token=admin)
        check(api(f"/mall-admin/product/update/{pid}", "POST", body, admin) == 1, "Product update changed no row")
        actual = api(f"/mall-admin/product/updateInfo/{pid}", token=admin)
        check(actual["subTitle"] == body["subTitle"], "Updated product was not persisted")
        fixture["productBody"] = body
        save()
    step("修改本次独立测试商品并回读", update)
    step("商城首页真实数据", lambda: api("/mall-portal/home/content"))
    step("真实商品分类", lambda: api("/mall-portal/product/categoryTreeList"))
    def search():
        data = api("/mall-portal/product/search?" + urllib.parse.urlencode({"keyword": fixture["productName"], "pageNum": 1, "pageSize": 10}))
        check(any(row["id"] == pid for row in data["list"]), "New product absent from portal search")
    step("前台搜索读取新增真实商品", search)
    detail = step("真实商品详情", lambda: api(f"/mall-portal/product/detail/{pid}"))
    check(detail["product"]["id"] == pid, "Wrong product detail")
    sku = api(f"/mall-admin/sku/{pid}", token=admin)[0]
    fixture["skuId"] = sku["id"]
    if "addressId" not in fixture:
        def address():
            data = {"name": "联调测试会员", "phoneNumber": member["telephone"], "defaultStatus": 1, "postCode": "000000",
                    "province": "广东省", "city": "深圳市", "region": "南山区", "detailAddress": "前后端联调测试地址，请勿发货"}
            check(api("/mall-portal/member/address/add", "POST", data, member_token) == 1, "Address not created")
            fixture["addressId"] = api("/mall-portal/member/address/list", token=member_token)[0]["id"]
            save()
        step("通过业务接口新增独立测试收货地址", address)
    def add_cart():
        items = api("/mall-portal/cart/list", token=member_token)
        existing = next((row for row in items if row["productSkuId"] == sku["id"]), None)
        if existing:
            api(f"/mall-portal/cart/update/quantity?id={existing['id']}&quantity=2", token=member_token)
        else:
            api("/mall-portal/cart/add", "POST", {"productId": pid, "productSkuId": sku["id"], "quantity": 2}, member_token)
        cart = next(row for row in api("/mall-portal/cart/list", token=member_token) if row["productSkuId"] == sku["id"])
        check(cart["quantity"] == 2, "Wrong cart quantity")
        fixture["cartId"] = cart["id"]
    step("加入购物车并回读真实数量", add_cart)
    step("真实结算确认单", lambda: api("/mall-portal/order/generateConfirmOrder", "POST", [fixture["cartId"]], member_token))
    before = api(f"/mall-admin/sku/{pid}", token=admin)[0]
    params = {"requestKey": "h5_e2e_" + uuid.uuid4().hex, "memberReceiveAddressId": fixture["addressId"],
              "cartIds": [fixture["cartId"]], "payType": 1, "useIntegration": 0}
    result = step("创建真实未支付订单", lambda: api("/mall-portal/order/generateOrder", "POST", params, member_token))
    order = result["order"]
    check(order["status"] == 0 and isinstance(order["orderSn"], str), "Unexpected order state")
    fixture["orderId"] = order["id"]
    fixture["orderSn"] = order["orderSn"]
    save()
    repeated = step("同请求键重试返回同一订单", lambda: api("/mall-portal/order/generateOrder", "POST", params, member_token))
    check(repeated["order"]["id"] == order["id"], "Duplicate checkout created another order")
    step("查询会员真实订单列表", lambda: api("/mall-portal/order/list?status=-1&pageNum=1&pageSize=10", token=member_token))
    queried = step("查询本人真实订单详情", lambda: api(f"/mall-portal/order/detail/{order['id']}", token=member_token))
    check(queried["id"] == order["id"], "Wrong order detail")
    reserved = api(f"/mall-admin/sku/{pid}", token=admin)[0]
    step("核对下单库存预留", lambda: check(reserved["stock"] == before["stock"] and reserved["lockStock"] == before["lockStock"] + 2, "Reservation mismatch"))
    step("通过会员业务接口取消测试订单", lambda: api(f"/mall-portal/order/cancelUserOrder?orderId={order['id']}", "POST", {}, member_token))
    after = api(f"/mall-admin/sku/{pid}", token=admin)[0]
    step("核对取消后库存恢复", lambda: check(after["stock"] == before["stock"] and after["lockStock"] == before["lockStock"], "Stock not restored"))
    step("回读测试订单已关闭状态", lambda: check(api(f"/mall-portal/order/detail/{order['id']}", token=member_token)["status"] == 4, "Cancelled order is not closed"))
    RESULTS.append({"name": "真实支付", "result": "NOT_RUN", "reason": "商户未配置；没有调用客户端声明支付成功接口"})
    return {"productId": pid, "memberId": fixture["memberId"], "orderId": order["id"], "inventory": {
        "before": {k: before[k] for k in ("stock", "lockStock")}, "reserved": {k: reserved[k] for k in ("stock", "lockStock")},
        "afterCancel": {k: after[k] for k in ("stock", "lockStock")}}}


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8", line_buffering=True)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        assert success(200, {"code": 200})
        assert not success(200, {"code": 500})
        assert not success(500, {"code": 200})
        assert not success(200, [])
        print("Business success contract self-check PASS")
        sys.exit(0)
    STATE.mkdir(exist_ok=True)
    report = {"time": datetime.now().isoformat(timespec="seconds"), "gateway": GATEWAY, "tests": RESULTS}
    try:
        report["evidence"] = run()
        report["pass"] = True
    except Exception as error:
        report["pass"] = False
        report["error"] = type(error).__name__ + ": " + str(error)
        print(report["error"], file=sys.stderr)
    (STATE / "e2e-api-results.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), "utf-8")
    sys.exit(0 if report["pass"] else 1)
