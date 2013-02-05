/*
 * @author Thomas Fischer
 * @since 04.02.2013
 */

#include "switch.h"
#include "gpio.h"
#include "xparameters.h"

static XGpio switches;

// forward declaration from led
int set_LED(u32 state);
// forward declaration of the callback procedure
void callbackSwitches();

/**
 * The Xil_ExceptionHandler to be used as callback for the switch component.
 * This handler calls the user-defined callback method and clears the interrupt.
 */
void GpioHandlerSwitches ( void *CallbackRef ) {
    XGpio *GpioPtr = (XGpio *)CallbackRef;

    // execute user-defined callback
    callbackSwitches();

    // Clear the Interrupt
    XGpio_InterruptClear(GpioPtr, GPIO_CHANNEL1);
}

u32 read_switch ( ) {
    return XGpio_DiscreteRead(&switches, GPIO_CHANNEL1);
}

int init_switch() {
	xil_printf("switches\n");
	int status = XGpio_Initialize(&switches, XPAR_DIP_SWITCHES_8BITS_DEVICE_ID);
	XGpio_SetDataDirection(&switches,  GPIO_CHANNEL1, 0xFFFFFFFF);
	status = GpioIntrSetup(&switches, XPAR_DIP_SWITCHES_8BITS_DEVICE_ID,
			XPAR_MICROBLAZE_0_INTC_DIP_SWITCHES_8BITS_IP2INTC_IRPT_INTR, GPIO_CHANNEL1, GpioHandlerSwitches);
	return status;
}

