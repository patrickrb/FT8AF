package com.k1af.ft8af.flex;

/**
 * Flex response types
 * @author BGY70Z
 * @date 2023-03-20
 */
public enum FlexResponseStyle {
    STATUS,//Status info, S+HANDLE
    RESPONSE,//Command response, R+client command sequence number
    HANDLE,//Handle assigned by the radio, H+handle (32-bit hex)
    VERSION,//Version number, V+version number
    MESSAGE,//Message sent by the radio, M+message number|message text
    COMMAND,//Send command, C+sequence number|command
    UNKNOW//Unknown response type
}
