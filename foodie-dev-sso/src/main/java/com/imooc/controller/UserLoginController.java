package com.imooc.controller;

import com.imooc.pojo.Users;
import com.imooc.pojo.vo.UserVO;
import com.imooc.service.UserService;
import com.imooc.utils.*;
import com.sun.deploy.net.HttpResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.TTCCLayout;
import org.aspectj.weaver.ast.Var;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import springfox.documentation.annotations.ApiIgnore;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.UUID;

//@Controller
@Slf4j
@Controller
public class UserLoginController {

    @Autowired
    private UserService userService;

    @Autowired
    private RedisOperator redisOperator;
    // token
    public static final String USER_REDIS_TOKEN = "user_redis_token";
    // 全局ticket
    public static final String USER_GLOBAL_TICKET = "user_global_ticket";
    // 临时ticket
    public static final String USER_TEMP_TICKET = "user_temp_ticket";
    // 存入cookie中的全局票据
    public static final String COOKIE_USER_TICKET = "cookie_user_ticket";

    @GetMapping("/login")
    public String login(String returnUrl,
                        Model model,
                        HttpServletRequest request) {
        model.addAttribute("returnUrl", returnUrl);
        /**
         * 校验是否登录：
         *         1. 已登录：直接创建临时门票
         *         2. 未登录：跳转到CAS登录界面
         */
        String cookieValue = CookieUtils.getCookieValue(request, COOKIE_USER_TICKET);
        if(StringUtils.isBlank(cookieValue)) {
            return "login";
        }
        // 判断全局门票是否有效
        boolean isEffective = checkGlobalTicket(cookieValue);
        if(!isEffective) {
            return "login";
        }
        // 生成临时票据
        String tempTicket = createTempTicket();
        return "redirect:"+returnUrl+"?tmpTicket="+tempTicket;
    }

    @PostMapping("/doLogin")
    public String doLogin(String username,
                          String password,
                          String returnUrl,
                          Model model,
                          HttpServletResponse response,
                          HttpServletRequest request) {
        // 0. 判断用户名和密码必须不为空
        if (StringUtils.isBlank(username) ||
                StringUtils.isBlank(password)) {
            model.addAttribute("errmsg", "用户名或密码不能为空");
            return "login";
        }
        // 1. 实现登录
        Users userResult = null;
        try {
            userResult = userService.queryUserForLogin(username,
                    MD5Utils.getMD5Str(password));
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (userResult == null) {
            model.addAttribute("errmsg", "用户名或密码不正确");
            return "login";
        }
        /**
         * 登录成功
         * 1.创建用户会话
         * 2.创建用户全局门票
         * 3.创建临时票据
         */
        // 生成全局ticket 全局门票
        String userTicket = UUID.randomUUID().toString().trim();
        // 1.创建用户会话
        redisOperator.set(USER_REDIS_TOKEN + ":" + userResult.getId(), JsonUtils.objectToJson(userResult));
        // 2.创建用户全局门票
        redisOperator.set(USER_GLOBAL_TICKET + ":" + userTicket, userResult.getId());
        // 存入cookie中,其他系统登录校验cookie中的值
        saveCookie(COOKIE_USER_TICKET, userTicket, response);
        // 3.生成临时票据
        String tempTicket = createTempTicket();

        return "redirect:"+returnUrl+"?tmpTicket="+tempTicket;
    }

    /**
     * 校验临时票据并且销毁
     * @param tmpTicket
     * @return
     */
    @PostMapping("/verifyTmpTicket")
    @ResponseBody
    public IMOOCJSONResult verifyTmpTicket(String tmpTicket, HttpServletRequest request) {
        if(StringUtils.isBlank(tmpTicket)) {
            return IMOOCJSONResult.errorLoginMap("临时票据不存在");
        }
        // 从redis中获取临时票据
        String tmpTicketValue = redisOperator.get(USER_TEMP_TICKET + ":" + tmpTicket);
        if(StringUtils.isBlank(tmpTicketValue)) {
            return IMOOCJSONResult.errorLoginMap("临时票据信息有误");
        }
        try {
            if(!MD5Utils.getMD5Str(tmpTicket).equals(tmpTicketValue)) {
                return IMOOCJSONResult.errorLoginMap("临时票据信息不一致");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        // 通过全局门票获取用户信息
        // 从cookie中获取全局门票redis key值
        String cookieValue = CookieUtils.getCookieValue(request, COOKIE_USER_TICKET);
        // 通过全局ticket获取用户ID
        String userId = redisOperator.get(USER_GLOBAL_TICKET + ":" + cookieValue);
        // 通过userID从token中获取用户信息
        String userJson = redisOperator.get(USER_REDIS_TOKEN + ":" + userId);
        if(userJson == null) {
            return IMOOCJSONResult.errorLoginMap("获取用户信息有误");
        }
        // 销毁临时ticket
        redisOperator.del(USER_TEMP_TICKET + ":" + tmpTicket);

        return IMOOCJSONResult.ok(JsonUtils.jsonToPojo(userJson, Users.class));
    }


    @PostMapping("/logout")
    @ResponseBody
    public IMOOCJSONResult logout(String userId, HttpServletRequest request) {
        if(StringUtils.isBlank(userId)) {
            return IMOOCJSONResult.errorLoginMap("退出失败");
        }
        // 获取全局门票cookie
        String cookieValue = CookieUtils.getCookieValue(request, COOKIE_USER_TICKET);
        if(StringUtils.isBlank(cookieValue)) {
            return IMOOCJSONResult.errorLoginMap("请勿重复退出");
        }
        // 移除全局门票
        redisOperator.del(USER_GLOBAL_TICKET + ":" + cookieValue);
        // 移除token值
        redisOperator.del(USER_REDIS_TOKEN + ":" + userId);

        return IMOOCJSONResult.ok();
    }

    /**
     * 创建临时ticket
     * @return
     */
    private String createTempTicket() {
        // 生成临时ticket
        String tempTicket = UUID.randomUUID().toString().trim();
        try {
            redisOperator.set(USER_TEMP_TICKET + ":" + tempTicket,  MD5Utils.getMD5Str(tempTicket), 600);
        } catch (Exception e) {
            e.printStackTrace();
            log.error("生成临时票据失败");
        }
        return tempTicket;
    }

    /**
     * 存入全局cookie到redis中
     * @param cookieName
     * @param cookieValue
     * @param response
     */
    private void saveCookie(String cookieName, String cookieValue,HttpServletResponse response) {
        Cookie cookie = new Cookie(cookieName, cookieValue);
        cookie.setDomain("sso.com");
        cookie.setPath("/");
        response.addCookie(cookie);
    }

    /**
     * 校验全局门票是否有效
     * @param userTicket
     * @return
     */
    private boolean checkGlobalTicket(String userTicket) {
        boolean isFlag = false;
        if(StringUtils.isBlank(userTicket)) {
            return isFlag;
        }
        // 校验全局门票是否过期
        String userId = redisOperator.get(USER_GLOBAL_TICKET + ":" + userTicket);
        if(StringUtils.isBlank(userId)) {
            return isFlag;
        }
        // 通过userID从token中获取用户信息
        String userJson = redisOperator.get(USER_REDIS_TOKEN + ":" + userId);
        if(userJson == null) {
            return isFlag;
        }
        isFlag = true;

        return isFlag;
    }


}
