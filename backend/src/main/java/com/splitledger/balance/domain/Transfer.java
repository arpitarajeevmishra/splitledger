package com.splitledger.balance.domain;

import com.splitledger.common.money.Money;
import java.util.UUID;

/** One suggested payment: {@code from} should pay {@code to} this much. */
public record Transfer(UUID from, UUID to, Money amount) {
}
