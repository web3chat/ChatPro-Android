package com.fzm.chat.search.web3;



/**
 * Created by JB on 15/01/2022.
 */
public interface JsListener {
    void onRequestAccounts(long callbackId);

    void onEthCall(Web3Call txdata);

    void onSignTransaction(Web3Transaction transaction);

    void onSignPersonalMessage(int callbackId, String data);
    void onSignMessage(int callbackId, String data);
    void walletSwitchEthereumChain(int callbackId, String data);
    void walletAddEthereumChain(int callbackId, String data);
}
