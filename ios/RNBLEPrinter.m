#import <Foundation/Foundation.h>
#import <CoreBluetooth/CoreBluetooth.h>

#import "RNBLEPrinter.h"
#import "PrinterSDK.h"

@interface RNBLEPrinter () <CBCentralManagerDelegate>
@property (nonatomic, strong) CBCentralManager *bluetoothManager;
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
        m_printer = nil;
        self.bluetoothManager = [[CBCentralManager alloc] initWithDelegate:self queue:nil];
        successCallback(@[@"Init successful"]);
    } @catch (NSException *exception) {
        errorCallback(@[@"No Bluetooth adapter available"]);
    }
}

- (void)centralManagerDidUpdateState:(CBCentralManager *)central {
    NSLog(@"Bluetooth state updated: %ld", (long)central.state);
    if (central.state == CBManagerStatePoweredOn) {
        NSLog(@"Bluetooth is powered on.");
    } else {
        NSLog(@"Bluetooth is not powered on. Current state: %ld", (long)central.state);
    }
}

RCT_EXPORT_METHOD(isBluetoothReady:(RCTResponseSenderBlock)callback) {
    CBManagerState state = self.bluetoothManager.state;
    BOOL isReady = (state == CBManagerStatePoweredOn);
    callback(@[@(isReady)]);
}

RCT_EXPORT_METHOD(getDeviceList:(RCTResponseSenderBlock)successCallback
                  fail:(RCTResponseSenderBlock)errorCallback) {
    @try {
        if (!_printerArray) {
            [NSException raise:@"Null pointer exception" format:@"Must call init function first"];
        }

        [_printerArray removeAllObjects];

        [[PrinterSDK defaultPrinterSDK] scanPrintersWithCompletion:^(Printer* printer) {
            if (!printer) return;

            BOOL exists = NO;
            for (Printer *existing in _printerArray) {
                if ([existing.UUIDString isEqualToString:printer.UUIDString]) {
                    exists = YES;
                    break;
                }
            }
            if (!exists) {
                [_printerArray addObject:printer];
            }
        }];

        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(3.0 * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
            [[PrinterSDK defaultPrinterSDK] stopScanPrinters];

            NSMutableArray *mapped = [NSMutableArray arrayWithCapacity:[_printerArray count]];
            for (Printer *p in _printerArray) {
                NSDictionary *dict = @{
                    @"device_name": p.name ?: @"",
                    @"inner_mac_address": p.UUIDString ?: @""
                };
                [mapped addObject:dict];
            }
            successCallback(@[mapped]);
        });
    } @catch (NSException *exception) {
        errorCallback(@[exception.reason]);
    }
}

RCT_EXPORT_METHOD(connectPrinter:(NSString *)inner_mac_address
                  success:(RCTResponseSenderBlock)successCallback
                  fail:(RCTResponseSenderBlock)errorCallback) {
    @try {
        if (!_printerArray || [_printerArray count] == 0) {
            [NSException raise:@"Invalid state" format:@"No printers discovered. Call getDeviceList first."];
        }

        Printer *selectedPrinter = nil;
        for (Printer *p in _printerArray) {
            if ([inner_mac_address isEqualToString:p.UUIDString]) {
                selectedPrinter = p;
                break;
            }
        }

        if (!selectedPrinter) {
            [NSException raise:@"Invalid connection"
                        format:@"Printer with address %@ not found in discovered list", inner_mac_address];
        }

        __block id connectedObserver = nil;
        __block id disconnectedObserver = nil;
        __block BOOL callbackInvoked = NO;

        void (^cleanup)(void) = ^{
            if (connectedObserver) {
                [[NSNotificationCenter defaultCenter] removeObserver:connectedObserver];
                connectedObserver = nil;
            }
            if (disconnectedObserver) {
                [[NSNotificationCenter defaultCenter] removeObserver:disconnectedObserver];
                disconnectedObserver = nil;
            }
        };

        connectedObserver = [[NSNotificationCenter defaultCenter]
            addObserverForName:PrinterConnectedNotification
                        object:nil
                         queue:[NSOperationQueue mainQueue]
                    usingBlock:^(NSNotification *note) {
            if (callbackInvoked) return;
            callbackInvoked = YES;
            cleanup();
            m_printer = selectedPrinter;
            NSDictionary *printerInfo = @{
                @"device_name": selectedPrinter.name ?: @"",
                @"inner_mac_address": selectedPrinter.UUIDString ?: @""
            };
            successCallback(@[printerInfo]);
        }];

        disconnectedObserver = [[NSNotificationCenter defaultCenter]
            addObserverForName:PrinterDisconnectedNotification
                        object:nil
                         queue:[NSOperationQueue mainQueue]
                    usingBlock:^(NSNotification *note) {
            if (callbackInvoked) return;
            callbackInvoked = YES;
            cleanup();
            errorCallback(@[[NSString stringWithFormat:@"Printer %@ disconnected during connection", inner_mac_address]]);
        }];

        [[PrinterSDK defaultPrinterSDK] connectBT:selectedPrinter];

        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(10.0 * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
            if (callbackInvoked) return;
            callbackInvoked = YES;
            cleanup();
            errorCallback(@[[NSString stringWithFormat:@"Connection to printer %@ timed out", inner_mac_address]]);
        });

    } @catch (NSException *exception) {
        errorCallback(@[exception.reason]);
    }
}

RCT_EXPORT_METHOD(printRawData:(NSString *)text
                  success:(RCTResponseSenderBlock)successCallback
                  fail:(RCTResponseSenderBlock)errorCallback) {
    @try {
        if (!m_printer) {
            [NSException raise:@"Invalid connection" format:@"Not connected to a printer"];
        }

        NSData *decodedData = [[NSData alloc] initWithBase64EncodedString:text options:0];

        if (decodedData && decodedData.length > 0) {
            NSMutableString *hexString = [NSMutableString stringWithCapacity:decodedData.length * 2];
            const unsigned char *bytes = decodedData.bytes;
            for (NSUInteger i = 0; i < decodedData.length; i++) {
                [hexString appendFormat:@"%02x", bytes[i]];
            }
            [[PrinterSDK defaultPrinterSDK] sendHex:hexString];
        } else {
            [[PrinterSDK defaultPrinterSDK] printText:text];
        }

        if (successCallback) {
            successCallback(@[@"Done"]);
        }
    } @catch (NSException *exception) {
        if (errorCallback) {
            errorCallback(@[exception.reason]);
        }
    }
}

RCT_EXPORT_METHOD(printImageData:(NSString *)imageUrl
                  opts:(NSDictionary *)opts
                  fail:(RCTResponseSenderBlock)errorCallback) {
    @try {
        if (!m_printer) {
            [NSException raise:@"Invalid connection" format:@"Not connected to a printer"];
        }

        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
            @try {
                NSURL *url = [NSURL URLWithString:imageUrl];
                NSData *imageData = [NSData dataWithContentsOfURL:url];
                if (!imageData) {
                    dispatch_async(dispatch_get_main_queue(), ^{
                        errorCallback(@[@"Failed to download image"]);
                    });
                    return;
                }

                UIImage *image = [UIImage imageWithData:imageData];
                if (!image) {
                    dispatch_async(dispatch_get_main_queue(), ^{
                        errorCallback(@[@"Failed to create image from downloaded data"]);
                    });
                    return;
                }

                dispatch_async(dispatch_get_main_queue(), ^{
                    [[PrinterSDK defaultPrinterSDK] printImage:image];
                });
            } @catch (NSException *exception) {
                dispatch_async(dispatch_get_main_queue(), ^{
                    errorCallback(@[exception.reason]);
                });
            }
        });
    } @catch (NSException *exception) {
        errorCallback(@[exception.reason]);
    }
}

RCT_EXPORT_METHOD(printImageBase64:(NSString *)base64Data
                  opts:(NSDictionary *)opts
                  fail:(RCTResponseSenderBlock)errorCallback) {
    @try {
        if (!m_printer) {
            [NSException raise:@"Invalid connection" format:@"Not connected to a printer"];
        }

        NSData *imageData = [[NSData alloc] initWithBase64EncodedString:base64Data options:0];
        if (!imageData) {
            errorCallback(@[@"Failed to decode base64 image data"]);
            return;
        }

        UIImage *image = [UIImage imageWithData:imageData];
        if (!image) {
            errorCallback(@[@"Failed to create image from decoded data"]);
            return;
        }

        [[PrinterSDK defaultPrinterSDK] printImage:image];
    } @catch (NSException *exception) {
        errorCallback(@[exception.reason]);
    }
}

RCT_EXPORT_METHOD(closeConn) {
    @try {
        if (m_printer) {
            [[PrinterSDK defaultPrinterSDK] disconnect];
            m_printer = nil;
        }
    } @catch (NSException *exception) {
        NSLog(@"closeConn error: %@", exception.reason);
    }
}

@end
