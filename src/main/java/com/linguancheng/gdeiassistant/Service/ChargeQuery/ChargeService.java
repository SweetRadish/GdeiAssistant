package com.linguancheng.gdeiassistant.Service.ChargeQuery;

import com.linguancheng.gdeiassistant.Exception.ChargeException.AmountNotAvailableException;
import com.linguancheng.gdeiassistant.Exception.ChargeException.RequestExpiredException;
import com.linguancheng.gdeiassistant.Exception.CommonException.NetWorkTimeoutException;
import com.linguancheng.gdeiassistant.Exception.CommonException.ServerErrorException;
import com.linguancheng.gdeiassistant.Pojo.Entity.Charge;
import com.linguancheng.gdeiassistant.Pojo.Entity.ChargeLog;
import com.linguancheng.gdeiassistant.Pojo.HttpClient.HttpClientSession;
import com.linguancheng.gdeiassistant.Repository.Mysql.GdeiAssistantLogs.Charge.ChargeMapper;
import com.linguancheng.gdeiassistant.Service.CardQuery.CardQueryService;
import com.linguancheng.gdeiassistant.Tools.HttpClientUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpResponse;
import org.apache.http.client.CookieStore;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.cookie.Cookie;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ChargeService {

    private int timeout;

    @Autowired
    private CardQueryService cardQueryService;

    @Autowired
    private ChargeMapper chargeMapper;

    private Log log = LogFactory.getLog(ChargeService.class);

    @Value("#{propertiesReader['timeout.charge']}")
    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    /**
     * 提交校园卡充值请求并自动确认，返回支付宝URL和Cookie列表
     *
     * @param sessionId
     * @param username
     * @param password
     * @param amount
     * @return
     */
    public Charge ChargeRequest(String sessionId, String username, String password, int amount) throws Exception {
        CloseableHttpClient httpClient = null;
        CookieStore cookieStore = null;
        try {
            if (amount <= 0 || amount > 500) {
                throw new AmountNotAvailableException("充值金额超过范围");
            }
            HttpClientSession httpClientSession = HttpClientUtils.getHttpClient(sessionId, false, timeout);
            httpClient = httpClientSession.getCloseableHttpClient();
            cookieStore = httpClientSession.getCookieStore();
            //登录支付管理平台
            cardQueryService.LoginCardSystem(httpClient, username, password);
            //发送充值请求
            Map<String, String> ecardDataMap = SendChargeRequest(httpClient, amount);
            //确认充值请求
            return ConfirmChargeRequest(sessionId, httpClient, ecardDataMap);
        } catch (AmountNotAvailableException e) {
            log.error("校园卡充值异常：", e);
            throw new AmountNotAvailableException("用户充值金额超过范围");
        } catch (IOException e) {
            log.error("校园卡充值异常：", e);
            throw new NetWorkTimeoutException("网络连接超时");
        } catch (Exception e) {
            log.error("校园卡充值异常：", e);
            throw new ServerErrorException("支付管理系统异常");
        } finally {
            if (httpClient != null) {
                try {
                    httpClient.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (cookieStore != null) {
                HttpClientUtils.SyncHttpClientCookieStore(sessionId, cookieStore);
            }
        }
    }

    /**
     * 发送充值请求
     *
     * @param httpClient
     * @param amount
     * @throws Exception
     */
    private Map<String, String> SendChargeRequest(CloseableHttpClient httpClient, int amount)
            throws Exception {
        HttpPost httpPost = new HttpPost("http://ecard.gdei.edu.cn/CardManage/CardInfo/DoPay");
        BasicNameValuePair basicNameValuePair1 = new BasicNameValuePair("fbankno", "epay");
        BasicNameValuePair basicNameValuePair2 = new BasicNameValuePair("tobankno", "card");
        BasicNameValuePair basicNameValuePair3 = new BasicNameValuePair("Amount", String.valueOf(amount));
        BasicNameValuePair basicNameValuePair4 = new BasicNameValuePair("password", "");
        BasicNameValuePair basicNameValuePair5 = new BasicNameValuePair("checkXieYi", "on");
        List<BasicNameValuePair> basicNameValuePairs = new ArrayList<>();
        basicNameValuePairs.add(basicNameValuePair1);
        basicNameValuePairs.add(basicNameValuePair2);
        basicNameValuePairs.add(basicNameValuePair3);
        basicNameValuePairs.add(basicNameValuePair4);
        basicNameValuePairs.add(basicNameValuePair5);
        //提交表单
        httpPost.setEntity(new UrlEncodedFormEntity(basicNameValuePairs, StandardCharsets.UTF_8));
        HttpResponse httpResponse = httpClient.execute(httpPost);
        Document document = Jsoup.parse(EntityUtils.toString(httpResponse.getEntity()));
        if (httpResponse.getStatusLine().getStatusCode() == 200 && (document.title().equals("Error")) || document.title().equals("广东第二师范学院支付平台")) {
            //身份凭证过期，重新连接
            throw new RequestExpiredException("身份凭证过期");
        } else {
            if (httpResponse.getStatusLine().getStatusCode() == 302) {
                HttpGet httpGet = new HttpGet("http://ecard.gdei.edu.cn/SynPay/Pay");
                httpResponse = httpClient.execute(httpGet);
                document = Jsoup.parse(EntityUtils.toString(httpResponse.getEntity()));
                if (httpResponse.getStatusLine().getStatusCode() == 200 && document.title().equals("服务平台-提交支付请求")) {
                    //进入提交支付请求页面，获取请求参数，并模拟JS进行表单提交
                    List<BasicNameValuePair> basicNameValuePairList = new ArrayList<>();
                    Elements inputs = document.getElementsByTag("input");
                    for (Element element : inputs) {
                        String name = element.attr("name");
                        String value = element.attr("value");
                        if (!name.isEmpty()) {
                            basicNameValuePairList.add(new BasicNameValuePair(name, value));
                        }
                    }
                    httpPost = new HttpPost("https://epay.gdei.edu.cn:8443/synpay/web/doPay");
                    //绑定表单参数
                    httpPost.setEntity(new UrlEncodedFormEntity(basicNameValuePairList, StandardCharsets.UTF_8));
                    httpResponse = httpClient.execute(httpPost);
                    document = Jsoup.parse(EntityUtils.toString(httpResponse.getEntity()));
                    if (httpResponse.getStatusLine().getStatusCode() == 200 && document.title().equals("广东第二师范学院支付平台")) {
                        //成功提交请求,检查支付平台实际预留的信息是否一致
                        Element bd = document.getElementsByClass("bd").first();
                        String name = bd.select("h3").first().text();
//                        if (!name.equals(chargeXm)) {
//                            //信息不一致，中止交易
//                            throw new InconsistentInformationException("用户信息不一致");
//                        }
                        httpGet = new HttpGet("https://epay.gdei.edu.cn:8443/synpay/web/disOrderInfo");
                        httpResponse = httpClient.execute(httpGet);
                        document = Jsoup.parse(EntityUtils.toString(httpResponse.getEntity()));
                        if (httpResponse.getStatusLine().getStatusCode() == 200 && document.title().equals("广东第二师范学院支付平台")) {
                            //获取存放充值信息的DIV
                            Element main_hd = document.getElementsByClass("main_hd").first();
                            String confirmNumber = main_hd.select("span").get(0).text();
                            String confirmName = main_hd.select("span").get(1).text();
                            String confirmAmount;
                            if (amount <= 100) {
                                //小数额交易
                                confirmAmount = document.getElementsByClass("pri smallnum").first().text().substring(1);
                            } else {
                                //大数额交易
                                confirmAmount = document.getElementsByClass("pri").first().text().substring(1);
                            }
                            Map<String, String> ecardDataMap = new HashMap<>();
                            Elements ecardDatas = document.getElementsByTag("input");
                            for (Element ecardData : ecardDatas) {
                                ecardDataMap.put(ecardData.attr("name"), ecardData.attr("value"));
                            }
                            return ecardDataMap;
                        }
                        throw new ServerErrorException();
                    }
                    throw new ServerErrorException();
                }
                throw new ServerErrorException();
            }
            throw new ServerErrorException();
        }
    }

    /**
     * 确认充值请求
     *
     * @param sessionId
     * @param httpClient
     * @param ecardDataMap
     * @return
     * @throws Exception
     */
    private Charge ConfirmChargeRequest(String sessionId, CloseableHttpClient httpClient, Map<String, String> ecardDataMap) throws Exception {
        Charge charge = new Charge();
        List<BasicNameValuePair> basicNameValuePairs = new ArrayList<>();
        for (Map.Entry<String, String> entry : ecardDataMap.entrySet()) {
            basicNameValuePairs.add(new BasicNameValuePair(entry.getKey(), entry.getValue()));
        }
        HttpPost httpPost = new HttpPost("https://epay.gdei.edu.cn:8443/synpay/web/forwardPayTool");
        //绑定表单参数
        httpPost.setEntity(new UrlEncodedFormEntity(basicNameValuePairs, StandardCharsets.UTF_8));
        HttpResponse httpResponse = httpClient.execute(httpPost);
        Document document = Jsoup.parse(EntityUtils.toString(httpResponse.getEntity()));
        if (httpResponse.getStatusLine().getStatusCode() == 200 && document.title().equals("广东第二师范学院支付平台")) {
            Elements inputs = document.getElementsByTag("input");
            List<BasicNameValuePair> mapiDataList = new ArrayList<>();
            for (Element input : inputs) {
                String inputName = input.attr("name");
                String inputValue = input.attr("value");
                mapiDataList.add(new BasicNameValuePair(inputName, inputValue));
            }
            httpPost = new HttpPost("https://mapi.alipay.com/gateway.do?_input_charset=utf-8");
            //绑定表单参数
            httpPost.setEntity(new UrlEncodedFormEntity(mapiDataList, StandardCharsets.UTF_8));
            httpResponse = httpClient.execute(httpPost);
            if (httpResponse.getStatusLine().getStatusCode() == 302) {
                HttpGet httpGet = new HttpGet(httpResponse.getFirstHeader("Location").getValue());
                httpResponse = httpClient.execute(httpGet);
                if (httpResponse.getStatusLine().getStatusCode() == 200) {
                    //获取订单支付URL
                    document = Jsoup.parse(EntityUtils.toString(httpResponse.getEntity()));
                    String J_orderId = document.getElementById("J_orderId").attr("value");
                    if (J_orderId != null && !J_orderId.isEmpty()) {
                        //提交确认充值请求成功
                        String url = "https://excashier.alipay.com/standard/auth.htm?payOrderId=" + J_orderId;
                        httpGet = new HttpGet(url);
                        httpClient.execute(httpGet);
                        //获取Cookies
                        List<Cookie> cookieList = HttpClientUtils.GetHttpClientCookieStore(sessionId);
                        //保存支付宝充值接口URL和Cookies信息
                        charge.setCookieList(cookieList);
                        charge.setAlipayURL(url);
                        return charge;
                    }
                    throw new ServerErrorException();
                }
                throw new ServerErrorException();
            }
            throw new ServerErrorException();
        } else if (httpResponse.getStatusLine().getStatusCode() == 200) {
            //会话超时关闭
            throw new RequestExpiredException("会话超时关闭");
        }
        throw new ServerErrorException();
    }

    /**
     * 保存用户充值记录日志
     *
     * @param username
     * @param amount
     */
    @Async
    public void SaveChargeLog(String username, int amount) throws Exception {
        ChargeLog chargeLog = new ChargeLog();
        chargeLog.setUsername(username);
        chargeLog.setAmount(amount);
        chargeMapper.insertChargeLog(chargeLog);
    }
}
