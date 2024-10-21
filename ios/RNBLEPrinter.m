#import <Foundation/Foundation.h>
#import <CoreBluetooth/CoreBluetooth.h>

#import "RNBLEPrinter.h"
#import "PrinterSDK.h"

@interface RNBLEPrinter () <CBCentralManagerDelegate>
@property (nonatomic, strong) CBCentralManager *bluetoothManager;
@property (nonatomic, strong) dispatch_queue_t bluetoothQueue;
@property (nonatomic, strong) NSMutableArray<Printer *> *discoveredPrinters;
@property (nonatomic, weak) Printer *connectedPrinter; // Weak reference to avoid retain cycles
@property (nonatomic, assign) BOOL bluetoothReady; // Property to track Bluetooth readiness
@end

@implementation RNBLEPrinter

- (dispatch_queue_t)methodQueue
{
    return dispatch_get_main_queue();
}

RCT_EXPORT_MODULE()

RCT_EXPORT_METHOD(init:(RCTResponseSenderBlock)successCallback
                  fail:(RCTResponseSenderBlock)errorCallback) {
    @try {
        _printerArray = [NSMutableArray new];
        m_printer = [[NSObject alloc] init];
        self.bluetoothManager = [[CBCentralManager alloc] initWithDelegate:self queue:nil];
        self.bluetoothReady = NO;
        successCallback(@[@"Init successful"]);
    } @catch (NSException *exception) {
        errorCallback(@[@"No Bluetooth adapter available"]);
    }
}

- (void)centralManagerDidUpdateState:(CBCentralManager *)central {
    NSLog(@"Bluetooth state updated: %ld", (long)central.state);
    if (central.state == CBManagerStatePoweredOn) {
        self.bluetoothReady = YES; // Set ready to YES
        NSLog(@"Bluetooth is powered on.");
        
        // Start scanning for printers now that Bluetooth is ready
        [[PrinterSDK defaultPrinterSDK] scanPrintersWithCompletion:^(Printer* printer) {
            // Add printer handling code here if needed
        }];
    } else {
        self.bluetoothReady = NO; // Set ready to NO for other states
        NSLog(@"Bluetooth is not powered on. Current state: %ld", (long)central.state);
    }
}


RCT_EXPORT_METHOD(isBluetoothReady:(RCTResponseSenderBlock)callback) {
    CBManagerState state = self.bluetoothManager.state;
    BOOL isReady = (state == CBManagerStatePoweredOn);
    NSLog(@"isBluetoothReady called, Bluetooth state: %ld, bluetoothReady: %@", state, isReady ? @"YES" : @"NO");
    callback(@[@(isReady)]);
}


RCT_EXPORT_METHOD(getDeviceList:(RCTResponseSenderBlock)successCallback
                  fail:(RCTResponseSenderBlock)errorCallback) {
    @try {
        !_printerArray ? [NSException raise:@"Null pointer exception" format:@"Must call init function first"] : nil;
        [[PrinterSDK defaultPrinterSDK] scanPrintersWithCompletion:^(Printer* printer){
            [_printerArray addObject:printer];
            NSMutableArray *mapped = [NSMutableArray arrayWithCapacity:[_printerArray count]];
            [_printerArray enumerateObjectsUsingBlock:^(id obj, NSUInteger idx, BOOL *stop) {
                NSDictionary *dict = @{ @"device_name" : printer.name, @"inner_mac_address" : printer.UUIDString};
                [mapped addObject:dict];
            }];
            NSMutableArray *uniquearray = (NSMutableArray *)[[NSSet setWithArray:mapped] allObjects];;
            successCallback(@[uniquearray]);
        }];
    } @catch (NSException *exception) {
        errorCallback(@[exception.reason]);
    }
}

RCT_EXPORT_METHOD(connectPrinter:(NSString *)inner_mac_address
                  success:(RCTResponseSenderBlock)successCallback
                  fail:(RCTResponseSenderBlock)errorCallback) {
    @try {
        __block BOOL found = NO;
        __block Printer* selectedPrinter = nil;
        [_printerArray enumerateObjectsUsingBlock: ^(id obj, NSUInteger idx, BOOL *stop){
            selectedPrinter = (Printer *)obj;
            if ([inner_mac_address isEqualToString:(selectedPrinter.UUIDString)]) {
                found = YES;
                *stop = YES;
            }
        }];

        if (found) {
            [[PrinterSDK defaultPrinterSDK] connectBT:selectedPrinter];
            [[NSNotificationCenter defaultCenter] postNotificationName:@"BLEPrinterConnected" object:nil];
            m_printer = selectedPrinter;
            successCallback(@[[NSString stringWithFormat:@"Connected to printer %@", selectedPrinter.name]]);
        } else {
            [NSException raise:@"Invalid connection" format:@"connectPrinter: Can't connect to printer %@", inner_mac_address];
        }
    } @catch (NSException *exception) {
        errorCallback(@[exception.reason]);
    }
}

RCT_EXPORT_METHOD(printRawData:(NSString *)text
                  success:(RCTResponseSenderBlock)successCallback
                  fail:(RCTResponseSenderBlock)errorCallback) {
    @try {
        !m_printer ? [NSException raise:@"Invalid connection" format:@"printRawData: Can't connect to printer"] : nil;

        double delayInSeconds = 2.0f;
        dispatch_time_t popTime = dispatch_time(DISPATCH_TIME_NOW, (int64_t)(delayInSeconds * NSEC_PER_SEC));
        dispatch_after(popTime, dispatch_get_main_queue(), ^(void)
        {
            NSData *decodedData = [[NSData alloc] initWithBase64EncodedString:text options:0];
            NSString *decodedString = [[NSString alloc] initWithData:decodedData encoding:NSUTF8StringEncoding];
            [[PrinterSDK defaultPrinterSDK] printText:decodedString];
            successCallback(@[@"PRINTED"]);
        });
    } @catch (NSException *exception) {
        errorCallback(@[exception.reason]);
    }
}

RCT_EXPORT_METHOD(closeConn) {
    @try {
        !m_printer ? [NSException raise:@"Invalid connection" format:@"closeConn: Can't connect to printer"] : nil;
        [[PrinterSDK defaultPrinterSDK] disconnect];
        m_printer = nil;
    } @catch (NSException *exception) {
        NSLog(@"%@", exception.reason);
    }
}

@end

