package colossus
package testkit

import core._

import akka.actor._
import akka.testkit.TestProbe

trait MockConnection extends Connection with MockChannelActions {
  
  /**
   * Simulate event-loop iterations, calling readyForData until this buffer
   * fills or everything is written.  This can be used to test backpressure
   * situations
   *
   * Be aware you need to call clearBuffer yourself
   */
  def iterate[T](f: => T): T = {
    val res = f
    while (writeReadyEnabled && handleWrite(new encoding.DynamicBuffer)) {}
    res
  }

  /**
   * Simulates event loop iteration, clearing the buffer on each iteration to avoid any backpressure
   */
  def iterateAndClear() {
    while (writeReadyEnabled) {
      handleWrite(new encoding.DynamicBuffer)
      clearBuffer()
    }
  }
    

  def iterate() = iterate[Unit]({})

  def disrupt() {
    close(DisconnectCause.Closed)
  }

  def testWrite(d: DataBuffer): WriteStatus = write(d)


  def workerProbe: TestProbe
  def serverProbe: Option[TestProbe]


}

trait TypedMockConnection[T <: ConnectionHandler] extends MockConnection{

  def typedHandler: T
}

object MockConnection {


  def server[T <: ServerConnectionHandler](handlerF: ServerContext => T, _maxWriteSize: Int = 1024)
  (implicit sys: ActorSystem): ServerConnection with TypedMockConnection[T] = {
    val (_serverProbe, server) = FakeIOSystem.fakeServerRef
    val fw = FakeIOSystem.fakeWorker
    val ctx = ServerContext(server, fw.worker.generateContext())
    val _handler = handlerF(ctx)
    _handler.setBind()
    new ServerConnection(_handler.context.id, _handler, server, fw.worker) with TypedMockConnection[T] {
      def maxWriteSize = _maxWriteSize
      def workerProbe = fw.probe
      def serverProbe = Some(_serverProbe)
      def typedHandler = _handler
    }
  }

  def client[T <: ClientConnectionHandler](_handler: T, fakeworker: FakeWorker, _maxWriteSize: Int)(implicit sys: ActorSystem): ClientConnection with TypedMockConnection[T] = {
    new ClientConnection(_handler.id, _handler, fakeworker.worker) with TypedMockConnection[T] {
      def maxWriteSize = _maxWriteSize
      def workerProbe = fakeworker.probe
      def serverProbe = None
      def typedHandler = _handler //don't rename _handler to handler, since Connection already has a member with that name
    }

  }


  def client[T <: ClientConnectionHandler](handlerF: Context => T, _maxWriteSize: Int = 1024 )(implicit sys: ActorSystem): ClientConnection with TypedMockConnection[T] = {
    val fakeworker = FakeIOSystem.fakeWorker
    val ctx = fakeworker.worker.generateContext()
    val handler = handlerF(ctx)
    handler.setBind()
    client(handler, fakeworker, _maxWriteSize)
  }
}