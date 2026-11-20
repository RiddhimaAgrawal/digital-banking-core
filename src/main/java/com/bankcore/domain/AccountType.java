package com.bankcore.domain;

/** SYSTEM accounts are owned by the bank itself (e.g. the cash clearing account) and never by a customer. */
public enum AccountType {
    CHECKING, SAVINGS, SYSTEM
}
