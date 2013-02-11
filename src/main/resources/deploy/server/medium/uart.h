/**
 * Handles communication over UART/USB.
 * This includes medium-specific initialisation as well as the listening loop.
 * @file
 * @author Thomas Fischer
 * @since 05.02.2013
 */

#ifndef UART_H_
#define UART_H_

/** initialise this communication medium */
void init_medium();

/**
 * Start listening for in-going packages
 * @returns A negative value, if failed. Otherwise this procedure will not return.
 *          If it does return anyway without failing, it will return 0.
 */
int start_application();

#endif /* UART_H_ */
