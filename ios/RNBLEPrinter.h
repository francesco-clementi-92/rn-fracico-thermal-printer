//
//  RNBLEPrinter.h
//  RNThermalReceiptPrinter
//
//  Created by MTT on 06/10/19.
//  Copyright © 2019 Facebook. All rights reserved.
//
#pragma once
#ifndef RNBLEPrinter_h
#define RNBLEPrinter_h

#if __has_include("RCTBridgeModule.h")
#import "RCTBridgeModule.h"
#else
#import <React/RCTBridgeModule.h>
#endif
#import <CoreBluetooth/CoreBluetooth.h>

@class Printer;

@interface RNBLEPrinter : NSObject <RCTBridgeModule>{
    NSMutableArray<Printer *>* _printerArray;
    Printer* m_printer;
}
@end

#endif /* RNBLEPrinter_h */
