package dev.codingtransfer.plugin;
final class TransferCompletionText {
    private TransferCompletionText(){}
    static String format(String message,int total){return total>0?message+" "+total+"/"+total:message;}
}
