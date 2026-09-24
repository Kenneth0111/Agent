package com.example.creator.account;

import com.example.creator.auth.UserRepository;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Profile("dev")
@Order(1)
class DevAccountSeeds implements ApplicationRunner {
    private final UserRepository users;
    private final AccountService accounts;

    DevAccountSeeds(UserRepository users, AccountService accounts) {
        this.users = users;
        this.accounts = accounts;
    }

    @Override
    public void run(ApplicationArguments args) {
        users.findByEmail("creator-a@example.test").ifPresent(user -> {
            if (accounts.ownedBy(user.getId()).isEmpty()) {
                accounts.create(user.getId(), new AccountService.AccountInput("Java 八股与托福跟读", "程序员",
                        "每周两条 Java 面试快问快答、一条托福英语跟读，以录屏和口播为主",
                        List.of("Java 面试快问快答", "托福英语跟读"), 3));
            }
        });
        users.findByEmail("creator-b@example.test").ifPresent(user -> {
            if (accounts.ownedBy(user.getId()).isEmpty()) {
                accounts.create(user.getId(), new AccountService.AccountInput("生活记录测试号", "普通用户",
                        "用于验证多用户与多账号隔离的内部内容账号",
                        List.of("日常生活"), 1));
            }
        });
    }
}
