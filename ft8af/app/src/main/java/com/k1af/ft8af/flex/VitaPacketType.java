package com.k1af.ft8af.flex;

public enum VitaPacketType {
    IF_DATA,//IF Data packet without Stream Identifier
    IF_DATA_WITH_STREAM,//IF Data packet with Stream Identifier
    EXT_DATA,//Extension Data packet without Stream Identifier
    EXT_DATA_WITH_STREAM,//Extension Data packet with Stream Identifier
    IF_CONTEXT,//IF Context packet(see Section 7)
    EXT_CONTEXT//Extension Context packet(see Section 7);
}
