package sample.core.events.internal;

import aie.guard.Violates;

@Violates("A-17")
class EventsSendsHttpItself {

    java.net.http.HttpClient field;
}
