package com.linguancheng.gdeiassistant.Controller.GradeQuery;

import com.linguancheng.gdeiassistant.Annotation.QueryLog;
import com.linguancheng.gdeiassistant.Annotation.RestQueryLog;
import com.linguancheng.gdeiassistant.Enum.Base.ServiceResultEnum;
import com.linguancheng.gdeiassistant.Enum.Query.QueryMethodEnum;
import com.linguancheng.gdeiassistant.Pojo.Document.GradeDocument;
import com.linguancheng.gdeiassistant.Pojo.Entity.Grade;
import com.linguancheng.gdeiassistant.Pojo.Entity.User;
import com.linguancheng.gdeiassistant.Pojo.GradeQuery.GradeQueryJsonResult;
import com.linguancheng.gdeiassistant.Pojo.GradeQuery.GradeQueryResult;
import com.linguancheng.gdeiassistant.Pojo.UserLogin.UserLoginResult;
import com.linguancheng.gdeiassistant.Service.GradeQuery.GradeCacheService;
import com.linguancheng.gdeiassistant.Service.GradeQuery.GradeQueryService;
import com.linguancheng.gdeiassistant.Service.UserLogin.UserLoginService;
import com.linguancheng.gdeiassistant.Tools.StringUtils;
import com.linguancheng.gdeiassistant.ValidGroup.User.UserLoginValidGroup;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by linguancheng on 2017/7/22.
 */

@Controller
public class GradeQueryController {

    @Autowired
    private GradeQueryService gradeQueryService;

    @Autowired
    private GradeCacheService gradeCacheService;

    @Autowired
    private UserLoginService userLoginService;

    @RequestMapping(value = "/grade")
    public ModelAndView ResolveGradePage() {
        return new ModelAndView("Grade/grade");
    }

    /**
     * 成绩查询Rest接口
     *
     * @param request
     * @param user
     * @param year
     * @param method
     * @param timestamp
     * @param bindingResult
     * @return
     */
    @RequestMapping(value = "/rest/gradequery", method = RequestMethod.POST)
    @RestQueryLog
    @ResponseBody
    public GradeQueryJsonResult GradeQuery(HttpServletRequest request
            , @ModelAttribute("user") @Validated(value = UserLoginValidGroup.class) User user
            , BindingResult bindingResult, Integer year, Long timestamp
            , @RequestParam(value = "method", required = false
            , defaultValue = "0") QueryMethodEnum method) {
        GradeQueryJsonResult result = new GradeQueryJsonResult();
        if (bindingResult.hasErrors()) {
            result.setSuccess(false);
            result.setErrorMessage("API接口已更新，请更新应用至最新版本");
        } else if (year != null && (year < 0 || year > 3)) {
            result.setSuccess(false);
            result.setErrorMessage("请求参数不合法");
        } else {
            //校验用户账号身份
            UserLoginResult userLoginResult = userLoginService.UserLogin(request, user, true);
            GradeQueryResult gradeQueryResult = null;
            switch (userLoginResult.getLoginResultEnum()) {
                case SERVER_ERROR:
                    result.setSuccess(false);
                    result.setEmpty(false);
                    result.setErrorMessage("教务系统维护中，请稍后再试");
                    break;

                case TIME_OUT:
                    result.setSuccess(false);
                    result.setEmpty(false);
                    result.setErrorMessage("网络连接超时，请重试");

                case PASSWORD_ERROR:
                    result.setSuccess(false);
                    result.setEmpty(false);
                    result.setErrorMessage("密码已更新，请重新登录");
                    break;

                case LOGIN_SUCCESS:
                    switch (method) {
                        case CACHE_FIRST:
                            //优先查询缓存
                            gradeQueryResult = GetUserGradeDocument(user.getUsername(), year);
                            switch (gradeQueryResult.getGradeServiceResultEnum()) {
                                case SUCCESS:
                                    //查询成功
                                    result.setSuccess(true);
                                    result.setFirstTermGPA(gradeQueryResult.getFirstTermGPA());
                                    result.setFirstTermIGP(gradeQueryResult.getFirstTermIGP());
                                    result.setSecondTermGPA(gradeQueryResult.getSecondTermGPA());
                                    result.setSecondTermIGP(gradeQueryResult.getSecondTermIGP());
                                    result.setFirstTermGradeList(gradeQueryResult.getFirstTermGradeList());
                                    result.setSecondTermGradeList(gradeQueryResult.getSecondTermGradeList());
                                    result.setQueryYear(gradeQueryResult.getQueryYear());
                                    break;

                                case EMPTY_RESULT:
                                    //缓存无数据，获取教务系统成绩数据
                                    gradeQueryResult = QueryGradeData(request, user, year, timestamp);
                                    switch (gradeQueryResult.getGradeServiceResultEnum()) {
                                        case SUCCESS:
                                            //查询成功
                                            result.setSuccess(true);
                                            result.setFirstTermGPA(gradeQueryResult.getFirstTermGPA());
                                            result.setFirstTermIGP(gradeQueryResult.getFirstTermIGP());
                                            result.setSecondTermGPA(gradeQueryResult.getSecondTermGPA());
                                            result.setSecondTermIGP(gradeQueryResult.getSecondTermIGP());
                                            result.setFirstTermGradeList(gradeQueryResult.getFirstTermGradeList());
                                            result.setSecondTermGradeList(gradeQueryResult.getSecondTermGradeList());
                                            result.setQueryYear(gradeQueryResult.getQueryYear());
                                            break;

                                        case ERROR_CONDITION:
                                            //学期不可查询
                                            result.setSuccess(false);
                                            result.setEmpty(true);
                                            result.setErrorMessage("当前学期暂不可查询");
                                            break;

                                        case PASSWORD_INCORRECT:
                                            //用户密码错误
                                            result.setSuccess(false);
                                            result.setEmpty(false);
                                            result.setErrorMessage("密码已更新，请重新登录");
                                            break;

                                        case TIME_OUT:
                                            //网络连接超时
                                            result.setSuccess(false);
                                            result.setEmpty(false);
                                            result.setErrorMessage("网络连接超时，请重试");
                                            break;

                                        case TIMESTAMP_INVALID:
                                            //时间戳失效
                                            result.setSuccess(false);
                                            result.setEmpty(false);
                                            result.setErrorMessage("时间戳校验失败，请尝试重新登录");
                                            break;

                                        case SERVER_ERROR:
                                        default:
                                            //教务系统异常
                                            result.setSuccess(false);
                                            result.setEmpty(false);
                                            result.setErrorMessage("教务系统维护中，请稍后再试");
                                            break;
                                    }
                                    break;

                                case ERROR_CONDITION:
                                    //学期不可查询
                                    result.setSuccess(false);
                                    result.setEmpty(true);
                                    result.setErrorMessage("当前学期暂不可查询");
                                    break;
                            }
                            break;

                        case CACHE_ONLY:
                            //只查询缓存
                            gradeQueryResult = GetUserGradeDocument(user.getUsername(), year);
                            switch (gradeQueryResult.getGradeServiceResultEnum()) {
                                case SUCCESS:
                                    //查询成功
                                    result.setSuccess(true);
                                    result.setFirstTermGPA(gradeQueryResult.getFirstTermGPA());
                                    result.setFirstTermIGP(gradeQueryResult.getFirstTermIGP());
                                    result.setSecondTermGPA(gradeQueryResult.getSecondTermGPA());
                                    result.setSecondTermIGP(gradeQueryResult.getSecondTermIGP());
                                    result.setFirstTermGradeList(gradeQueryResult.getFirstTermGradeList());
                                    result.setSecondTermGradeList(gradeQueryResult.getSecondTermGradeList());
                                    result.setQueryYear(gradeQueryResult.getQueryYear());
                                    break;

                                case EMPTY_RESULT:
                                    //缓存无数据
                                    result.setSuccess(false);
                                    result.setEmpty(false);
                                    result.setErrorMessage("用户成绩信息未同步，请稍后再试");
                                    break;

                                case ERROR_CONDITION:
                                    //学期不可查询
                                    result.setSuccess(false);
                                    result.setEmpty(true);
                                    result.setErrorMessage("当前学期暂不可查询");
                                    break;
                            }
                            break;

                        case QUERY_ONLY:
                            //只查询教务系统
                            gradeQueryResult = QueryGradeData(request, user, year, timestamp);
                            switch (gradeQueryResult.getGradeServiceResultEnum()) {
                                case SUCCESS:
                                    //查询成功
                                    result.setSuccess(true);
                                    result.setFirstTermGPA(gradeQueryResult.getFirstTermGPA());
                                    result.setFirstTermIGP(gradeQueryResult.getFirstTermIGP());
                                    result.setSecondTermGPA(gradeQueryResult.getSecondTermGPA());
                                    result.setSecondTermIGP(gradeQueryResult.getSecondTermIGP());
                                    result.setFirstTermGradeList(gradeQueryResult.getFirstTermGradeList());
                                    result.setSecondTermGradeList(gradeQueryResult.getSecondTermGradeList());
                                    result.setQueryYear(gradeQueryResult.getQueryYear());
                                    break;

                                case ERROR_CONDITION:
                                    //学期不可查询
                                    result.setSuccess(false);
                                    result.setEmpty(true);
                                    result.setErrorMessage("当前学期暂不可查询");
                                    break;

                                case PASSWORD_INCORRECT:
                                    //用户密码错误
                                    result.setSuccess(false);
                                    result.setEmpty(false);
                                    result.setErrorMessage("密码已更新，请重新登录");
                                    break;

                                case TIME_OUT:
                                    //网络连接超时
                                    result.setSuccess(false);
                                    result.setEmpty(false);
                                    result.setErrorMessage("网络连接超时，请重试");
                                    break;

                                case TIMESTAMP_INVALID:
                                    //时间戳失效
                                    result.setSuccess(false);
                                    result.setEmpty(false);
                                    result.setErrorMessage("时间戳校验失败，请尝试重新登录");
                                    break;

                                case SERVER_ERROR:
                                default:
                                    //教务系统异常
                                    result.setSuccess(false);
                                    result.setEmpty(false);
                                    result.setErrorMessage("教务系统维护中，请稍后再试");
                                    break;
                            }
                            break;

                        default:
                            result.setSuccess(false);
                            result.setErrorMessage("请求参数不合法");
                            break;
                    }
            }
        }
        return result;
    }

    /**
     * 成绩查询
     *
     * @param request
     * @param year
     * @param method
     * @return
     */
    @RequestMapping(value = "/api/gradequery", method = RequestMethod.POST)
    @QueryLog
    @ResponseBody
    public GradeQueryJsonResult GradeQuery(HttpServletRequest request
            , Integer year, @RequestParam(value = "method", required = false
            , defaultValue = "0") QueryMethodEnum method) {
        GradeQueryJsonResult result = new GradeQueryJsonResult();
        if (year != null && (year < 0 || year > 3)) {
            result.setSuccess(false);
            result.setErrorMessage("请求参数不合法");
        } else {
            String username = (String) request.getSession().getAttribute("username");
            String password = (String) request.getSession().getAttribute("password");
            GradeQueryResult gradeQueryResult = null;
            switch (method) {
                case CACHE_FIRST:
                    //优先查询缓存
                    gradeQueryResult = GetUserGradeDocument(username, year);
                    switch (gradeQueryResult.getGradeServiceResultEnum()) {
                        case SUCCESS:
                            //查询成功
                            result.setSuccess(true);
                            result.setFirstTermGPA(gradeQueryResult.getFirstTermGPA());
                            result.setFirstTermIGP(gradeQueryResult.getFirstTermIGP());
                            result.setSecondTermGPA(gradeQueryResult.getSecondTermGPA());
                            result.setSecondTermIGP(gradeQueryResult.getSecondTermIGP());
                            result.setFirstTermGradeList(gradeQueryResult.getFirstTermGradeList());
                            result.setSecondTermGradeList(gradeQueryResult.getSecondTermGradeList());
                            result.setQueryYear(gradeQueryResult.getQueryYear());
                            break;

                        case EMPTY_RESULT:
                            //缓存无数据，获取教务系统成绩数据
                            gradeQueryResult = QueryGradeData(request, new User(username, password)
                                    , year, null);
                            switch (gradeQueryResult.getGradeServiceResultEnum()) {
                                case SUCCESS:
                                    //查询成功
                                    result.setSuccess(true);
                                    result.setFirstTermGPA(gradeQueryResult.getFirstTermGPA());
                                    result.setFirstTermIGP(gradeQueryResult.getFirstTermIGP());
                                    result.setSecondTermGPA(gradeQueryResult.getSecondTermGPA());
                                    result.setSecondTermIGP(gradeQueryResult.getSecondTermIGP());
                                    result.setFirstTermGradeList(gradeQueryResult.getFirstTermGradeList());
                                    result.setSecondTermGradeList(gradeQueryResult.getSecondTermGradeList());
                                    result.setQueryYear(gradeQueryResult.getQueryYear());
                                    break;

                                case ERROR_CONDITION:
                                    //学期不可查询
                                    result.setSuccess(false);
                                    result.setEmpty(true);
                                    result.setErrorMessage("当前学期暂不可查询");
                                    break;

                                case PASSWORD_INCORRECT:
                                    //用户密码错误
                                    result.setSuccess(false);
                                    result.setEmpty(false);
                                    result.setErrorMessage("密码已更新，请重新登录");
                                    break;

                                case TIME_OUT:
                                    //网络连接超时
                                    result.setSuccess(false);
                                    result.setEmpty(false);
                                    result.setErrorMessage("网络连接超时，请重试");
                                    break;

                                case TIMESTAMP_INVALID:
                                    //时间戳失效
                                    result.setSuccess(false);
                                    result.setEmpty(false);
                                    result.setErrorMessage("时间戳校验失败，请尝试重新登录");
                                    break;

                                case SERVER_ERROR:
                                default:
                                    //教务系统异常
                                    result.setSuccess(false);
                                    result.setEmpty(false);
                                    result.setErrorMessage("教务系统维护中，请稍后再试");
                                    break;
                            }
                            break;

                        case ERROR_CONDITION:
                            //学期不可查询
                            result.setSuccess(false);
                            result.setEmpty(true);
                            result.setErrorMessage("当前学期暂不可查询");
                            break;
                    }
                    break;

                case CACHE_ONLY:
                    gradeQueryResult = GetUserGradeDocument(username, year);
                    switch (gradeQueryResult.getGradeServiceResultEnum()) {
                        case SUCCESS:
                            //查询成功
                            result.setSuccess(true);
                            result.setFirstTermGPA(gradeQueryResult.getFirstTermGPA());
                            result.setFirstTermIGP(gradeQueryResult.getFirstTermIGP());
                            result.setSecondTermGPA(gradeQueryResult.getSecondTermGPA());
                            result.setSecondTermIGP(gradeQueryResult.getSecondTermIGP());
                            result.setFirstTermGradeList(gradeQueryResult.getFirstTermGradeList());
                            result.setSecondTermGradeList(gradeQueryResult.getSecondTermGradeList());
                            result.setQueryYear(gradeQueryResult.getQueryYear());
                            break;

                        case EMPTY_RESULT:
                            //缓存无数据
                            result.setSuccess(false);
                            result.setEmpty(false);
                            result.setErrorMessage("用户成绩信息未同步，请稍后再试");
                            break;

                        case ERROR_CONDITION:
                            //学期不可查询
                            result.setSuccess(false);
                            result.setEmpty(true);
                            result.setErrorMessage("当前学期暂不可查询");
                            break;
                    }
                    break;

                case QUERY_ONLY:
                    //只查询教务系统
                    gradeQueryResult = QueryGradeData(request, new User(username, password)
                            , year, null);
                    switch (gradeQueryResult.getGradeServiceResultEnum()) {
                        case SUCCESS:
                            //查询成功
                            result.setSuccess(true);
                            result.setFirstTermGPA(gradeQueryResult.getFirstTermGPA());
                            result.setFirstTermIGP(gradeQueryResult.getFirstTermIGP());
                            result.setSecondTermGPA(gradeQueryResult.getSecondTermGPA());
                            result.setSecondTermIGP(gradeQueryResult.getSecondTermIGP());
                            result.setFirstTermGradeList(gradeQueryResult.getFirstTermGradeList());
                            result.setSecondTermGradeList(gradeQueryResult.getSecondTermGradeList());
                            result.setQueryYear(gradeQueryResult.getQueryYear());
                            break;

                        case ERROR_CONDITION:
                            //学期不可查询
                            result.setSuccess(false);
                            result.setEmpty(true);
                            result.setErrorMessage("当前学期暂不可查询");
                            break;

                        case PASSWORD_INCORRECT:
                            //用户密码错误
                            result.setSuccess(false);
                            result.setEmpty(false);
                            result.setErrorMessage("密码已更新，请重新登录");
                            break;

                        case TIME_OUT:
                            //网络连接超时
                            result.setSuccess(false);
                            result.setEmpty(false);
                            result.setErrorMessage("网络连接超时，请重试");
                            break;

                        case TIMESTAMP_INVALID:
                            //时间戳失效
                            result.setSuccess(false);
                            result.setEmpty(false);
                            result.setErrorMessage("时间戳校验失败，请尝试重新登录");
                            break;

                        case SERVER_ERROR:
                        default:
                            //教务系统异常
                            result.setSuccess(false);
                            result.setEmpty(false);
                            result.setErrorMessage("教务系统维护中，请稍后再试");
                            break;
                    }
                    break;

                default:
                    result.setSuccess(false);
                    result.setErrorMessage("请求参数不合法");
                    break;
            }
        }
        return result;
    }

    /**
     * 从MongoDB中获取用户缓存的成绩信息
     *
     * @param username
     * @param year
     * @return
     */
    private GradeQueryResult GetUserGradeDocument(String username, Integer year) {
        GradeQueryResult gradeQueryResult = new GradeQueryResult();
        GradeDocument gradeDocument = gradeCacheService.ReadGrade(username);
        if (gradeDocument != null) {
            List<GradeDocument.GradeList> gradeLists = gradeDocument.getGradeList();
            if (year == null) {
                year = gradeLists.size() - 1;
            }
            if (gradeLists.size() == 0 || year >= gradeLists.size()) {
                gradeQueryResult.setGradeServiceResultEnum(ServiceResultEnum
                        .ERROR_CONDITION);
                return gradeQueryResult;
            }
            List<Grade> gradeList = gradeDocument.getGradeList().get(year)
                    .getGradeList();
            List<Grade> firstTermGradeList = new ArrayList<>();
            List<Grade> secondTermGradeList = new ArrayList<>();
            for (Grade grade : gradeList) {
                if (grade.getGrade_term().equals("1")) {
                    firstTermGradeList.add(grade);
                } else {
                    secondTermGradeList.add(grade);
                }
            }
            Double firstTermGPA = gradeDocument.getFirstTermGPAList().get(year);
            Double secondTermGPA = gradeDocument.getSecondTermGPAList().get(year);
            Double firstTermIGP = gradeDocument.getFirstTermIGPList().get(year);
            Double secondTermIGP = gradeDocument.getSecondTermIGPList().get(year);
            gradeQueryResult.setFirstTermGPA(firstTermGPA);
            gradeQueryResult.setFirstTermIGP(firstTermIGP);
            gradeQueryResult.setSecondTermGPA(secondTermGPA);
            gradeQueryResult.setSecondTermIGP(secondTermIGP);
            gradeQueryResult.setFirstTermGradeList(firstTermGradeList);
            gradeQueryResult.setSecondTermGradeList(secondTermGradeList);
            gradeQueryResult.setQueryYear(year);
            gradeQueryResult.setGradeServiceResultEnum(ServiceResultEnum.SUCCESS);
            return gradeQueryResult;
        }
        gradeQueryResult.setGradeServiceResultEnum(ServiceResultEnum.EMPTY_RESULT);
        return gradeQueryResult;
    }

    /**
     * 从教务系统获取用户的成绩信息
     *
     * @param request
     * @param user
     * @param year
     * @param timestamp
     * @return
     */
    private GradeQueryResult QueryGradeData(HttpServletRequest request, User user
            , Integer year, Long timestamp) {
        GradeQueryResult gradeQueryResult = new GradeQueryResult();
        if (year == null) {
            //若没有指定查询的学年，则进行默认学年查询
            year = -1;
        }
        //检测是否已与教务系统进行会话同步
        if (timestamp == null) {
            if (request.getSession().getAttribute("timestamp") == null) {
                //进行会话同步
                UserLoginResult userLoginResult = userLoginService.UserLogin(request
                        , user, false);
                switch (userLoginResult.getLoginResultEnum()) {
                    case LOGIN_SUCCESS:
                        timestamp = userLoginResult.getTimestamp();
                        if (StringUtils.isBlank(user.getKeycode())) {
                            user.setKeycode(userLoginResult.getUser().getKeycode());
                        }
                        if (StringUtils.isBlank(user.getNumber())) {
                            user.setNumber(userLoginResult.getUser().getNumber());
                        }
                        userLoginService.AsyncUpdateSession(request);
                        return gradeQueryService.GradeQuery(request, user.getUsername()
                                , user.getKeycode(), user.getNumber(), timestamp, year);

                    case TIME_OUT:
                        gradeQueryResult.setGradeServiceResultEnum(ServiceResultEnum.TIME_OUT);
                        break;

                    case PASSWORD_ERROR:
                        gradeQueryResult.setGradeServiceResultEnum(ServiceResultEnum.PASSWORD_INCORRECT);
                        break;

                    case SERVER_ERROR:
                        gradeQueryResult.setGradeServiceResultEnum(ServiceResultEnum.SERVER_ERROR);
                        break;
                }
            } else {
                timestamp = (Long) request.getSession().getAttribute("timestamp");
                gradeQueryService.GradeQuery(request, user.getUsername()
                        , user.getKeycode(), user.getNumber(), timestamp, year);
            }
        } else {
            return gradeQueryService.GradeQuery(request, user.getUsername()
                    , user.getKeycode(), user.getNumber(), timestamp, year);
        }
        return gradeQueryResult;
    }
}
