//
//  react-native-udp
//
//  Created by Mark Vayngrib on 05/10/15.
//  Copyright (c) 2015 Tradle, Inc. All rights reserved.
//

/**
 * @providesModule UdpSocket
 * @flow
 */

'use strict';

var inherits = require('inherits')
var EventEmitter = require('events').EventEmitter
var {
  DeviceEventEmitter,
  NativeModules
} = require('react-native');
var Sockets = NativeModules.UdpSockets
var base64 = require('base64-js')
var ipRegex = require('ip-regex')
var noop = function () {}
var instances = 0
var STATE = {
  UNBOUND: 0,
  BINDING: 1,
  BOUND: 2
}

module.exports = UdpSocket

function UdpSocket(options, onmessage) {
  EventEmitter.call(this)

  if (typeof options === 'string') options = { type: options }

  if (options.type !== 'udp4' && options.type !== 'udp6') {
    throw new Error('invalid udp socket type')
  }

  this.type = options.type
  this._ipv = Number(this.type.slice(3))
  this._ipRegex = ipRegex['v' + this._ipv]({ exact: true })
  this._id = instances++
  this._state = STATE.UNBOUND
  this._subscription = DeviceEventEmitter.addListener(
    'udp-' + this._id + '-data', this._onReceive.bind(this)
  );

  // ensure compatibility with node's EventEmitter
  if (!this.on) this.on = this.addListener.bind(this)

  if (onmessage) this.on('message', onmessage)

  Sockets.createSocket(this._id, {
    type: this.type
  }) // later
}

inherits(UdpSocket, EventEmitter)

UdpSocket.prototype._debug = function() {
  // for now
  if (typeof DEBUG !== 'undefined' && DEBUG) {
    var args = [].slice.call(arguments)
    args.unshift('socket-' + this._id)
    console.log.apply(console, args)
  }
}

UdpSocket.prototype.bind = function(port, address, callback) {
  var self = this

  if (this._state !== STATE.UNBOUND) throw new Error('Socket is already bound')

  if (typeof address === 'function') {
    callback = address
    address = undefined
  }

  if (!address) address = '0.0.0.0'

  if (!port) port = 0

  if (callback) this.once('listening', callback.bind(this))

  this._state = STATE.BINDING
  this._debug('binding, address:', address, 'port:', port)
  Sockets.bind(this._id, port, address, function(err, addr) {
    err = normalizeError(err)
    if (err) {
      // questionable: may want to self-destruct and
      // force user to create a new socket
      self._state = STATE.UNBOUND
      self._debug('failed to bind', err)
      if (callback) callback(err)
      return self.emit('error', err)
    }

    self._debug('bound to address:', addr.address, 'port:', addr.port)
    self._address = addr.address
    self._port = addr.port
    self._state = STATE.BOUND
    self.emit('listening')
  })
}

UdpSocket.prototype.close = function() {
  if (this._destroyed) return

  this._destroyed = true
  this._debug('closing')
  this._subscription.remove();

  Sockets.close(this._id, this._debug.bind(this, 'closed'))
  this.emit('close')
}

UdpSocket.prototype._onReceive = function(info) {
  this._debug('received', info)

  // from base64 string
  var buf = base64.toByteArray(info.data)
  var rinfo = {
    address: info.address,
    port: info.port,
    family: 'IPv4', // not necessarily
    size: buf.length
  }

  if (typeof Buffer !== 'undefined') buf = new Buffer(buf, 'binary')

  this.emit('message', buf, rinfo)
}

/**
 * socket.send(buf, offset, length, port, address, [callback])
 *
 * For UDP sockets, the destination port and IP address must be
 * specified. A string may be supplied for the address parameter, and it will
 * be resolved with DNS. An optional callback may be specified to detect any
 * DNS errors and when buf may be re-used. Note that DNS lookups will delay
 * the time that a send takes place, at least until the next tick. The only
 * way to know for sure that a send has taken place is to use the callback.
 *
 * If the socket has not been previously bound with a call to bind, it's
 * assigned a random port number and bound to the "all interfaces" address
 * (0.0.0.0 for udp4 sockets, ::0 for udp6 sockets).
 *
 * @param {Array|string} message to be sent
 * @param {number} offset Offset in the buffer where the message starts.
 * @param {number} length Number of bytes in the message.
 * @param {number} port destination port
 * @param {string} address destination IP
 * @param {function} callback Callback when message is done being delivered.
 *                            Optional.
 */
// UdpSocket.prototype.send = function (buf, host, port, cb) {
UdpSocket.prototype.send = function(buffer, offset, length, port, address, callback) {
  var self = this

  if (typeof port !== 'number') throw new Error('invalid port')
  if (!isValidIP(address, this._ipRegex)) throw new Error('invalid address')

  if (offset !== 0) throw new Error('Non-zero offset not supported yet')

  if (this._state === STATE.UNBOUND) {
    var args = [].slice.call(arguments)
    return this.bind(0, function(err) {
      if (err) return callback(err)

      self.send.apply(self, args)
    })
  }
  else if (this._state === STATE.BINDING) {
    // we're ok, GCDAsync(Udp)Socket handles queueing internally
  }

  callback = callback || noop
  var str
  if (typeof buffer === 'string') {
    console.warn('socket.send(): interpreting as base64')
    str = buffer
  }
  else if (typeof Buffer !== 'undefined' && Buffer.isBuffer(buffer)) {
    str = buffer.toString('base64')
  }
  else if (buffer instanceof Uint8Array || Array.isArray(buffer)) {
    str = base64.fromByteArray(buffer)
  }
  else {
    throw new Error('invalid message format')
  }

  self._debug('sending', buffer, str)
  Sockets.send(this._id, str, +port, address, function(err) {
    err = normalizeError(err)
    if (err) {
      self._debug('send failed', err)
      return callback(err)
    }

    self._debug('sent')
    callback()
  })
}

UdpSocket.prototype.address = function() {
  if (this._state !== STATE.BOUND) {
    throw new Error('socket is not bound yet')
  }

  return {
    address: this._address,
    port: this._port,
    family: 'IPv4'
  }
}

UdpSocket.prototype.setBroadcast = function(flag) {
  var self = this

  if (this._state !== STATE.BOUND) {
    throw new Error('you must bind before setBroadcast()')
  }

  Sockets.setBroadcast(this._id, flag, function(err) {
    err = normalizeError(err)
    if (err) {
      self._debug('failed to set broadcast', err)
      return self.emit('error', err)
    }
  });
}

UdpSocket.prototype.setTTL = function(ttl) {
  // nothing yet
}

UdpSocket.prototype.setMulticastTTL = function(ttl, callback) {
  // nothing yet
}

UdpSocket.prototype.setMulticastLoopback = function(flag, callback) {
  // nothing yet
}

UdpSocket.prototype.addMembership = function(multicastAddress, multicastInterface, callback) {
  // nothing yet
}

UdpSocket.prototype.dropMembership = function(multicastAddress, multicastInterface, callback) {
  // nothing yet
}

UdpSocket.prototype.ref = function() {
  // anything?
}

UdpSocket.prototype.unref = function() {
  // anything?
}

function isValidIP (address, ipRegex) {
  if (typeof address !== 'string') return false

  return ipRegex.test(address)
}

function normalizeError (err) {
  if (err) {
    if (typeof err === 'string') err = new Error(err)

    return err
  }
}