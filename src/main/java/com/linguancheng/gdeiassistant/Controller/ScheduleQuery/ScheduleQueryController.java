package com.linguancheng.gdeiassistant.Controller.ScheduleQuery;

import com.linguancheng.gdeiassistant.Annotation.QueryLog;
import com.linguancheng.gdeiassistant.Annotation.RestQueryLog;
import com.linguancheng.gdeiassistant.Enum.Base.LoginResultEnum;
import com.linguancheng.gdeiassistant.Enum.Base.ServiceResultEnum;
import com.linguancheng.gdeiassistant.Pojo.Document.ScheduleDocument;
import com.linguancheng.gdeiassistant.Pojo.Entity.Schedule;
import com.linguancheng.gdeiassistant.Pojo.Entity.User;
import com.linguancheng.gdeiassistant.Pojo.Result.BaseResult;
import com.linguancheng.gdeiassistant.Pojo.ScheduleQuery.ScheduleQueryJsonResult;
import com.linguancheng.gdeiassistant.Pojo.UserLogin.UserLoginResult;
import com.linguancheng.gdeiassistant.Service.ScheduleQuery.ScheduleCacheService;
import com.linguancheng.gdeiassistant.Service.ScheduleQuery.ScheduleQueryService;
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
import java.util.List;

@Controller
public class ScheduleQueryController {

    @Autowired
    private UserLoginService userLoginService;

    @Autowired
    private ScheduleQueryService scheduleQueryService;

    @Autowired
    private ScheduleCacheService scheduleCacheService;

    @RequestMapping(value = "schedule", method = RequestMethod.GET)
    public ModelAndView ResolveSchedulePage() {
        return new ModelAndView("Schedule/schedule");
    }

    /**
     * 课表查询Rest接口
     *
     * @param request
     * @param user
     * @param week
     * @param refresh
     * @param timestamp
     * @param bindingResult
     * @return
     */
    @RequestMapping(value = "/rest/schedulequery", method = RequestMethod.POST)
    @RestQueryLog
    @ResponseBody
    public ScheduleQueryJsonResult ScheduleQuery(HttpServletRequest request
            , @ModelAttribute("user") @Validated(value = UserLoginValidGroup.class) User user
            , BindingResult bindingResult, Integer week, Long timestamp
            , @RequestParam(name = "refresh", required = false
            , defaultValue = "false") Boolean refresh) {
        ScheduleQueryJsonResult scheduleQueryJsonResult = new ScheduleQueryJsonResult();
        if (bindingResult.hasErrors()) {
            scheduleQueryJsonResult.setSuccess(false);
            scheduleQueryJsonResult.setErrorMessage("API接口已更新，请更新应用至最新版本");
        } else if (week != null && (week < 0 || week > 20)) {
            scheduleQueryJsonResult.setSuccess(false);
            scheduleQueryJsonResult.setErrorMessage("请求参数不合法");
        } else {
            //校验用户账号身份
            UserLoginResult userLoginResult = userLoginService.UserLogin(request
                    , user, true);
            switch (userLoginResult.getLoginResultEnum()) {
                case LOGIN_SUCCESS:
                    if (!refresh) {
                        //优先查询缓存数据
                        ScheduleDocument scheduleDocument = scheduleCacheService
                                .ReadSchedule(user.getUsername());
                        if (scheduleDocument != null) {
                            if (week == null) {
                                week = scheduleQueryService.getCurrentWeek();
                            }
                            scheduleQueryJsonResult.setSuccess(true);
                            scheduleQueryJsonResult.setScheduleList(scheduleQueryService
                                    .getSpecifiedWeekSchedule(scheduleDocument
                                            .getScheduleList(), week));
                            scheduleQueryJsonResult.setSelectedWeek(week);
                            return scheduleQueryJsonResult;
                        }
                    }
                    //若缓存数据不存在或要求强制更新，则从教务系统获取
                    //检测是否已与教务系统进行会话同步
                    if (timestamp == null) {
                        //进行会话同步
                        userLoginResult = userLoginService.UserLogin(request, user
                                , false);
                        switch (userLoginResult.getLoginResultEnum()) {
                            case LOGIN_SUCCESS:
                                timestamp = userLoginResult.getTimestamp();
                                if (StringUtils.isBlank(user.getKeycode())) {
                                    user.setKeycode(userLoginResult.getUser().getKeycode());
                                }
                                if (StringUtils.isBlank(user.getNumber())) {
                                    user.setNumber(userLoginResult.getUser().getNumber());
                                }
                                break;

                            case SERVER_ERROR:
                                //服务器异常
                                scheduleQueryJsonResult.setSuccess(false);
                                scheduleQueryJsonResult.setErrorMessage("教务系统维护中，请稍候再试");
                                return scheduleQueryJsonResult;

                            case TIME_OUT:
                                //连接超时
                                scheduleQueryJsonResult.setSuccess(false);
                                scheduleQueryJsonResult.setErrorMessage("网络连接超时，请重试");
                                return scheduleQueryJsonResult;

                            case PASSWORD_ERROR:
                                //用户名或密码错误
                                scheduleQueryJsonResult.setSuccess(false);
                                scheduleQueryJsonResult.setErrorMessage("密码已更新，请重新登录");
                                return scheduleQueryJsonResult;
                        }
                    }
                    BaseResult<List<Schedule>, ServiceResultEnum> scheduleQueryResult = scheduleQueryService
                            .ScheduleQuery(request, user.getUsername(), user.getKeycode()
                                    , user.getNumber(), timestamp);
                    switch (scheduleQueryResult.getResultType()) {
                        case SERVER_ERROR:
                            //服务器异常
                            scheduleQueryJsonResult.setSuccess(false);
                            scheduleQueryJsonResult.setErrorMessage("教务系统维护中，请稍候再试");
                            break;

                        case TIME_OUT:
                            //连接超时
                            scheduleQueryJsonResult.setSuccess(false);
                            scheduleQueryJsonResult.setErrorMessage("网络连接超时，请稍候再试");
                            break;

                        case PASSWORD_INCORRECT:
                            //用户名或密码错误
                            scheduleQueryJsonResult.setSuccess(false);
                            scheduleQueryJsonResult.setErrorMessage("密码已更新，请重新登录");
                            break;

                        case SUCCESS:
                            //查询成功
                            scheduleQueryJsonResult.setSuccess(true);
                            if (week == null) {
                                //无指定查询周数，则默认返回当前周数课表
                                scheduleQueryJsonResult.setScheduleList(scheduleQueryService
                                        .getSpecifiedWeekSchedule(scheduleQueryResult.getResultData()
                                                , scheduleQueryService.getCurrentWeek()));
                                scheduleQueryJsonResult.setSelectedWeek(scheduleQueryService.getCurrentWeek());
                            } else if (week.equals(0)) {
                                //若周数指定为0，则返回所有周数的课表
                                scheduleQueryJsonResult.setScheduleList(scheduleQueryResult.getResultData());
                                scheduleQueryJsonResult.setSelectedWeek(0);
                            } else {
                                //返回指定周数的课表
                                scheduleQueryJsonResult.setScheduleList(scheduleQueryService
                                        .getSpecifiedWeekSchedule(scheduleQueryResult.getResultData()
                                                , week));
                                scheduleQueryJsonResult.setSelectedWeek(week);
                            }
                            break;
                    }
                    break;

                case PASSWORD_ERROR:
                    //用户名或密码错误
                    scheduleQueryJsonResult.setSuccess(false);
                    scheduleQueryJsonResult.setErrorMessage("密码已更新，请重新登录");
                    break;

                case TIME_OUT:
                    //连接超时
                    scheduleQueryJsonResult.setSuccess(false);
                    scheduleQueryJsonResult.setErrorMessage("网络连接超时，请稍候再试");
                    break;

                case SERVER_ERROR:
                    //服务器异常
                    scheduleQueryJsonResult.setSuccess(false);
                    scheduleQueryJsonResult.setErrorMessage("教务系统维护中，请稍候再试");
                    break;
            }
        }
        return scheduleQueryJsonResult;
    }

    /**
     * 课表查询
     *
     * @param request
     * @param week
     * @param refresh
     * @return
     */
    @RequestMapping(value = "/schedulequery", method = RequestMethod.POST)
    @QueryLog
    @ResponseBody
    public ScheduleQueryJsonResult ScheduleQuery(HttpServletRequest request
            , Integer week, @RequestParam(value = "refresh", required = false
            , defaultValue = "false") Boolean refresh) {
        ScheduleQueryJsonResult scheduleQueryJsonResult = new ScheduleQueryJsonResult();
        if ((week != null && (week < 0 || week > 20))) {
            scheduleQueryJsonResult.setSuccess(false);
            scheduleQueryJsonResult.setErrorMessage("查询的周数不合法");
            return scheduleQueryJsonResult;
        }
        String username = (String) request.getSession().getAttribute("username");
        if (!refresh) {
            //优先查询缓存课表数据
            ScheduleDocument scheduleDocument = scheduleCacheService.ReadSchedule(username);
            if (scheduleDocument != null) {
                if (week == null) {
                    week = scheduleQueryService.getCurrentWeek();
                }
                List<Schedule> scheduleList = scheduleQueryService.getSpecifiedWeekSchedule(scheduleDocument
                        .getScheduleList(), week);
                scheduleQueryJsonResult.setSuccess(true);
                scheduleQueryJsonResult.setScheduleList(scheduleList);
                scheduleQueryJsonResult.setSelectedWeek(week);
                return scheduleQueryJsonResult;
            }
        }
        //若缓存数据不存在或要求强制更新，则从教务系统获取
        //检测是否已与教务系统进行会话同步
        if (request.getSession().getAttribute("timestamp") != null) {
            //进行会话同步
            switch (userLoginService.SyncUpdateSession(request)) {
                case SUCCESS:
                    break;

                case TIME_OUT:
                    //连接超时
                    scheduleQueryJsonResult.setSuccess(false);
                    scheduleQueryJsonResult.setErrorMessage("网络连接超时，请重试");
                    return scheduleQueryJsonResult;

                case PASSWORD_INCORRECT:
                    //身份凭证异常
                    scheduleQueryJsonResult.setSuccess(false);
                    scheduleQueryJsonResult.setErrorMessage("用户凭证已过期，请重新登录");
                    return scheduleQueryJsonResult;

                default:
                    //服务器异常
                    scheduleQueryJsonResult.setSuccess(false);
                    scheduleQueryJsonResult.setErrorMessage("教务系统维护中,请稍候再试");
                    return scheduleQueryJsonResult;
            }
        }
        String keycode = (String) request.getSession().getAttribute("keycode");
        String number = (String) request.getSession().getAttribute("number");
        Long timestamp = (Long) request.getSession().getAttribute("timestamp");
        BaseResult<List<Schedule>, ServiceResultEnum> scheduleQueryResult = scheduleQueryService
                .ScheduleQuery(request, username, keycode, number, timestamp);
        switch (scheduleQueryResult.getResultType()) {
            case SUCCESS:
                //查询课表成功
                scheduleQueryJsonResult.setSuccess(true);
                if (week == null) {
                    //无指定查询周数，则默认返回当前周数课表
                    scheduleQueryJsonResult.setScheduleList(scheduleQueryService
                            .getSpecifiedWeekSchedule(scheduleQueryResult.getResultData()
                                    , scheduleQueryService.getCurrentWeek()));
                    scheduleQueryJsonResult.setSelectedWeek(scheduleQueryService.getCurrentWeek());
                } else if (week.equals(0)) {
                    //若周数指定为0，则返回所有周数的课表
                    scheduleQueryJsonResult.setScheduleList(scheduleQueryResult.getResultData());
                    scheduleQueryJsonResult.setSelectedWeek(0);
                } else {
                    //返回指定周数的课表
                    scheduleQueryJsonResult.setScheduleList(scheduleQueryService
                            .getSpecifiedWeekSchedule(scheduleQueryResult.getResultData()
                                    , week));
                    scheduleQueryJsonResult.setSelectedWeek(week);
                }
                break;

            case TIMESTAMP_INVALID:
            case PASSWORD_INCORRECT:
                scheduleQueryJsonResult.setSuccess(false);
                scheduleQueryJsonResult.setErrorMessage("用户凭证已过期，请重新登录");
                break;

            case TIME_OUT:
                //连接超时
                scheduleQueryJsonResult.setSuccess(false);
                scheduleQueryJsonResult.setErrorMessage("连接教务系统超时,请稍候再试");
                break;

            case SERVER_ERROR:
                //服务器异常
                scheduleQueryJsonResult.setSuccess(false);
                scheduleQueryJsonResult.setErrorMessage("教务系统维护中,请稍候再试");
                break;
        }
        return scheduleQueryJsonResult;
    }
}
