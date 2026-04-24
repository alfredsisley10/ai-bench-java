package com.omnibank.adminconsole;

import com.omnibank.accounts.consumer.api.ConsumerAccountService;
import com.omnibank.shared.domain.AccountNumber;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/api/v1/accounts")
public class AdminAccountController {

    private final ConsumerAccountService accounts;

    public AdminAccountController(ConsumerAccountService accounts) {
        this.accounts = accounts;
    }

    @PostMapping("/{accountNumber}/freeze")
    public void freeze(@org.springframework.web.bind.annotation.PathVariable String accountNumber,
                       @RequestBody FreezeRequest req) {
        accounts.freeze(AccountNumber.of(accountNumber), req.reason);
    }

    @PostMapping("/{accountNumber}/unfreeze")
    public void unfreeze(@org.springframework.web.bind.annotation.PathVariable String accountNumber) {
        accounts.unfreeze(AccountNumber.of(accountNumber));
    }

    @PostMapping("/{accountNumber}/close")
    public void close(@org.springframework.web.bind.annotation.PathVariable String accountNumber,
                      @RequestBody CloseRequest req) {
        accounts.close(AccountNumber.of(accountNumber), req.reason);
    }

    public record FreezeRequest(String reason) {}
    public record CloseRequest(String reason) {}
}
