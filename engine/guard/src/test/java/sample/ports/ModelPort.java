package sample.ports;

public interface ModelPort {

    Reply call(Request request);

    record Request(String prompt) {
    }

    record Reply(String text) {
    }
}
