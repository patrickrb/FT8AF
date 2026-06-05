package com.bg7yoz.ft8cn.rigs;

public class InstructionSet {
    public  static final int ICOM=0;
    public  static final int YAESU_2=1;//5-byte data, 817
    public  static final int YAESU_3_9=2;//9-digit frequency
    public  static final int YAESU_3_8=3;//8-digit frequency
    public  static final int YAESU_3_450=4;//PTT transmit via TX command
    public  static final int KENWOOD_TK90=5;//11-digit frequency, only usable in VFO mode
    public  static final int YAESU_DX10=6;//9-digit, DATA-U mode
    public  static final int KENWOOD_TS590=7;//11-digit frequency, VFO mode
    public  static final int GUOHE_Q900=8;//GuoHe Q900, 5-byte one-shot reception, different from YAESU gen-2
    public  static final int XIEGUG90S=9;//XieGu G90S, ICOM-compatible commands, but does not automatically send frequency changes; requires periodic polling
    public  static final int ELECRAFT=10;//Elecraft K3
    public  static final int FLEX_CABLE=11;//FLEX6000;
    public  static final int FLEX_NETWORK=12;//FLEX network mode connection;
    public  static final int XIEGU_6100=13;//XieGu X6100;
    public  static final int KENWOOD_TS2000=14;//Kenwood TS2000, transmit command is TX0;
    public  static final int WOLF_SDR_DIGU=15;//UA3REO Wolf SDR, DIG-U mode, compatible with YAESU 450D;
    public  static final int WOLF_SDR_USB=16;//UA3REO Wolf SDR, USB mode, compatible with YAESU 450D;

    public  static final int TRUSDX=17;//(tr)USDX;
    public  static final int KENWOOD_TS570=18;//KENWOOD TS-570D
    public  static final int YAESU_3_9_U_DIG=19;//KENWOOD TS-570D

    public static final int XIEGU_6100_FT8CNS=20;//XieGu 6100 ft8cns version
    public static final int YAESU_847=21;//Ft-847
    public static final int ICOM_756=22;//Ft-847
    public static final int YAESU_FT710=23;//FT-710, currently reuses DX10 instruction set; CAT read loop must stay off
    public static final int DISCOVERY_TX500=24;//Lab599 Discovery TX-500, TS-2000 compatible, DATA mode (MD6;)




}
