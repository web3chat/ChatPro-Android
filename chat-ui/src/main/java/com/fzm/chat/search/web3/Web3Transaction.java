package com.fzm.chat.search.web3;


import java.math.BigInteger;


public class Web3Transaction
{
    public final Address recipient;
    public final Address contract;
    public final Address sender;
    public final BigInteger value;
    public final BigInteger gasPrice;
    public BigInteger gasLimit;

    // EIP1559
    public BigInteger maxFeePerGas;
    public BigInteger maxPriorityFeePerGas;

    public final long nonce;
    public final String payload;
    public final long leafPosition;
    public final String description;

    public Web3Transaction(
            Address recipient,
            Address contract,
            BigInteger value,
            BigInteger gasPrice,
            BigInteger gasLimit,
            long nonce,
            String payload,
            long leafPosition)
    {
        this.recipient = recipient;
        this.contract = contract;
        this.sender = null;
        this.value = value;
        this.gasPrice = gasPrice;
        this.gasLimit = gasLimit;
        this.nonce = nonce;
        this.payload = payload;
        this.leafPosition = leafPosition;
        this.description = null;
        this.maxFeePerGas = BigInteger.ZERO;
        this.maxPriorityFeePerGas = BigInteger.ZERO;
    }


}
