package specification

import org.junit.runner.RunWith
import org.specs2.runner.JUnitRunner
import org.specs2.specification.Fragments

@RunWith(classOf[JUnitRunner])
class RequestSpecificationV2Spec extends RequestSpecificationSpec {

  override def is: Fragments = Fragments.create(fragments("/v2/request") :_*)
}
