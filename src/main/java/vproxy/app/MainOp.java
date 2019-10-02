package vproxy.app;

public interface MainOp {
    String key();

    int argCount();

    int order();

    int pre(MainCtx ctx, String[] args);

    int execute(MainCtx ctx, String[] args);
}
